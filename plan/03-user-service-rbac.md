# Story 03 — `user-service`: RBAC, Ownership & Method Security

**Depends on:** [Story 02 — common-security Library](02-common-security-library.md)
**Unlocks:** [Story 04 — order-service](04-order-service-oauth2-client.md) (calls this service's
`/internal` API), [Story 06 — Keycloak Realm Setup](06-keycloak-realm-setup.md)

## Goal

Build the first resource service: a pure OAuth2 resource server that owns the `User` entity and
demonstrates three different shapes of Spring Security method authorization
(`@Secured`, `@PreAuthorize`, `@PostAuthorize`) plus a service-to-service-only internal API.

## Context

Base package: `com.enterprise.security.userservice`. This service never issues tokens or calls
another service — it only *validates* incoming JWTs (via `common-security`'s
`KeycloakRoleConverter`) and decides what the caller may do. Coarse rules (which paths need
authentication at all) live in `SecurityConfig`; everything about *whose* data a caller may
touch lives in `UserService`'s method annotations — keep that split as you build this.

## Tasks

### Task 1 — Module dependencies

`user-service/build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-security"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.h2database:h2")
}
```

`spring-security-test` and `h2` are test-only: `spring-security-test` supplies the
`SecurityMockMvcRequestPostProcessors.jwt()` helper used in Task 8; H2 is an in-memory stand-in
for Postgres so tests never need a real database.

### Task 2 — `domain/User.java`

JPA entity, table `app_user`:

```java
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String email;

    @Column(name = "full_name")
    private String fullName;

    private String department;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected User() { }

    public User(String id, String username, String email, String fullName, String department, String tenantId) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.department = department;
        this.tenantId = tenantId;
    }

    // getters for every field; setters only for fullName, department, active
}
```

**The key design decision:** `id` is the Keycloak subject (JWT `sub` claim) directly, not a
generated surrogate key. That's what lets ownership checks later compare a path variable straight
against `authentication.name` with no identity-mapping lookup in between. Don't add `@GeneratedValue`
here.

### Task 3 — Repository and DTOs

`repository/UserRepository.java`:
```java
public interface UserRepository extends JpaRepository<User, String> {
    List<User> findByTenantId(String tenantId);
}
```

`dto/UserResponse.java` — a record with `id, username, email, fullName, department, tenantId,
active, createdAt`, plus a static factory `from(User user)` that maps every field 1:1. This is
what controllers return — never return the `User` entity itself from a controller.

`dto/UpdateUserRequest.java` — a record with two validated fields:
`@Size(max = 200) String fullName`, `@Size(max = 100) String department`.

`service/UserNotFoundException.java` — `extends RuntimeException`, constructor
`UserNotFoundException(String id)` calling `super("User not found: " + id)`.

### Task 4 — `UserService`: the method-security core of this story

`@Service @Transactional`, one field `UserRepository userRepository` (constructor injection).
Four methods, each demonstrating a different rule shape — build them exactly as specified, the
SpEL expressions are the part that's easy to get subtly wrong:

```java
@Transactional(readOnly = true)
@PostAuthorize("hasRole('ADMIN') or hasRole('SUPPORT') "
        + "or returnObject.id == authentication.name "
        + "or returnObject.tenantId == authentication.token.claims['tenant']")
public User getUser(String id) {
    return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
}
```
`@PostAuthorize` because the decision needs the *loaded* user's tenant, which isn't known from the
`id` argument alone. `authentication.token.claims['tenant']` reads the caller's own tenant claim
directly off the JWT (works because the resource-server `Authentication`'s principal is a `Jwt`).

```java
@Transactional(readOnly = true)
@Secured({"ROLE_ADMIN", "ROLE_SUPPORT"})
public List<User> listUsersInCurrentTenant() {
    String tenant = TenantContext.get();
    return userRepository.findByTenantId(tenant);
}
```
Plain role gate — no per-row logic needed since the query itself is already scoped to
`TenantContext.get()` (populated by `common-security`'s `TenantContextFilter`, wired in Task 6).

```java
@PreAuthorize("hasRole('ADMIN') or #id == authentication.name")
@Audited(action = "USER_UPDATE")
public User updateUser(String id, UpdateUserRequest request) {
    User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    if (request.fullName() != null) user.setFullName(request.fullName());
    if (request.department() != null) user.setDepartment(request.department());
    return userRepository.save(user);
}
```
`#id` binds to the method's `id` parameter — this is why the root `build.gradle.kts` compiler flag
`-parameters` (Story 01) matters: without parameter names in bytecode, `#id` wouldn't resolve.

```java
@PreAuthorize("hasRole('ADMIN')")
@Audited(action = "USER_DEACTIVATE")
public void deactivateUser(String id) {
    User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    user.setActive(false);
    userRepository.save(user);
}

@Transactional(readOnly = true)
@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
public User getUserForServiceCall(String id) {
    return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
}
```
`getUserForServiceCall` backs the `/internal` route (Task 5) that `order-service` will call in
Story 04. This method-level check is deliberately redundant with the route rule in Task 6 — see
the callout at the end of this story.

### Task 5 — Controllers

`web/UserController.java` — `@RestController @RequestMapping("/api/users")`:
- `GET /me` → `@AuthenticationPrincipal Jwt jwt`, returns `UserResponse.from(userService.getUser(jwt.getSubject()))`.
  No `@PreAuthorize` needed — it can only ever return the caller's own record.
- `GET /{id}` → `UserResponse.from(userService.getUser(id))`
- `GET` (list) → `userService.listUsersInCurrentTenant().stream().map(UserResponse::from).toList()`
- `PUT /{id}` → `@Valid @RequestBody UpdateUserRequest request`, returns
  `UserResponse.from(userService.updateUser(id, request))`
- `DELETE /{id}` → calls `userService.deactivateUser(id)`, returns `void`

`web/InternalUserController.java` — `@RestController @RequestMapping("/internal/users")`, one
endpoint: `GET /{id}` → `UserResponse.from(userService.getUserForServiceCall(id))`. This is the
service-to-service surface Story 04's `order-service` will call.

`web/UserExceptionHandler.java` — `@RestControllerAdvice`, one `@ExceptionHandler(UserNotFoundException.class)`
returning a 404 `ProblemDetail` (`title="User Not Found"`, `detail=ex.getMessage()`,
`type=.../errors/not-found`). Registered separately from `common-security`'s
`GlobalExceptionHandler` since Spring resolves the most specific `@ExceptionHandler` match across
*all* advice beans in the context — this one only needs to cover what's unique to this service.

### Task 6 — `config/SecurityConfig`

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            KeycloakRoleConverter keycloakRoleConverter,
                                            TenantContextFilter tenantContextFilter,
                                            RestAuthenticationEntryPoint authenticationEntryPoint,
                                            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(SecurityHeadersCustomizer.apiDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/internal/**").hasAnyRole("SERVICE", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}
```

Every bean parameter here (`KeycloakRoleConverter`, `TenantContextFilter`,
`RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`) comes from Story 02's
`CommonSecurityAutoConfiguration` — this class only *composes* them, it doesn't construct any of
them itself. `csrf().disable()` is safe here because the service is stateless (bearer tokens, no
cookies) — CSRF only matters for cookie-based session auth.

**Why `/internal/**` is gated at the route level *and* method level (Task 4's
`getUserForServiceCall`):** it's the first of several deliberately redundant checks that stack up
across the whole system — Story 09 (Istio `AuthorizationPolicy`) adds a third, mesh-level check on
this exact path. No single layer is trusted alone; see `docs/04-security-layers.md` in the
finished repo for the full picture once you reach that story.

### Task 7 — `application.yml`

```yaml
server:
  port: 8081

spring:
  application:
    name: user-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:user_service}
    username: ${DB_USERNAME:user_service}
    password: ${DB_PASSWORD:user_service}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8080/realms/enterprise}

security:
  resource:
    client-id: user-service

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true

logging:
  pattern:
    console: "%d{ISO8601} %-5level [%thread] %logger{36} - %msg [tenant=%X{auditTenant}] %n"
```

`security.resource.client-id: user-service` feeds `JwtRoleMappingProperties` (Story 02) — it's how
`KeycloakRoleConverter` knows to read `resource_access.user-service.roles` for this service's own
client-specific roles, on top of realm roles. `ddl-auto: update` is a demo-only convenience (see
Story 06 and the root README's "known simplifications") — a real deployment would use Flyway or
Liquibase migrations instead.

Add `src/main/java/com/enterprise/security/userservice/UserServiceApplication.java` — a plain
`@SpringBootApplication` class with a `main` method calling
`SpringApplication.run(UserServiceApplication.class, args)`.

### Task 8 — Tests

Create `src/test/resources/application-test.yml` (activated via `@ActiveProfiles("test")`) so
tests boot against H2 instead of Postgres and never depend on a running Keycloak:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
  security:
    oauth2:
      resourceserver:
        jwt:
          # jwk-set-uri resolves lazily (only when a token is actually decoded), unlike
          # issuer-uri which triggers eager OIDC discovery at context startup — keeps this
          # test context bootable without a running Keycloak.
          jwk-set-uri: http://localhost:65535/realms/test/protocol/openid-connect/certs
```

`UserServiceApplicationTests` — `@SpringBootTest @ActiveProfiles("test")`, one empty
`contextLoads()` test method. Trivial, but it's what catches a broken bean wiring (a missing
`@Bean` method, a typo in a property key) before you even get to writing feature tests.

`web/UserControllerSecurityTest` — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`.
In `@BeforeEach`, clear the repository and seed two users: `owner-1` (tenant-a) and `other-1`
(tenant-b). Build JWTs for `mockMvc.perform(...)` with
`.with(jwt().jwt(j -> j.subject("...").claim("tenant", "...")).authorities(role("USER")))` (a
small local `role(String)` helper returning `new SimpleGrantedAuthority("ROLE_" + role)`). Write
one test per rule you built in Task 4, asserting the HTTP status Spring Security actually
produces — **not** by re-reading your own annotations, but by hitting the endpoint and checking
the response:

| Scenario | Expected status |
|---|---|
| No JWT at all on a protected route | 401 |
| Owner reads their own profile (`GET /api/users/owner-1` as `owner-1`) | 200 |
| Owner reads a different tenant's user (`GET /api/users/other-1` as `owner-1`) | 403 |
| ADMIN reads any profile | 200 |
| Plain USER lists all users (`GET /api/users`) | 403 |
| SUPPORT lists all users | 200 |
| Owner updates their own profile | 200 |
| Owner updates someone else's profile | 403 |
| Plain USER deactivates another user | 403 |
| ADMIN deactivates another user | 200 |
| Plain USER token hits `/internal/users/{id}` | 403 |
| A `SERVICE`-role token hits `/internal/users/{id}` | 200 |

That's 12 cases across the table — write each as its own `@Test` method (some scenarios pair two
requests in one method, as the reference implementation does for the "forbidden vs allowed"
pairs), not one giant parameterized test; a junior engineer picking this up later should be able
to find and fix exactly one failing case without untangling the rest.

## Definition of done

- [ ] `./gradlew :user-service:test` passes all 12+ security scenarios
- [ ] `./gradlew :user-service:bootJar` succeeds
- [ ] Every method in `UserService` that changes state carries `@Audited`
- [ ] No controller method returns a `User` entity directly (always via `UserResponse.from(...)`)

## What NOT to do yet

No Keycloak realm, no Docker, no way to run this against a *real* token yet — that's
[Story 06](06-keycloak-realm-setup.md) and [Story 07](07-docker-compose-local-dev.md). For now,
"done" means the MockMvc test suite is green against mocked JWTs, exactly as the tests in Task 8
exercise it.
