# Story 04 — `order-service`: OAuth2 Client-Credentials Service-to-Service Auth

**Depends on:** [Story 03 — user-service](03-user-service-rbac.md) (calls its `/internal` API)
**Unlocks:** [Story 05 — admin-service](05-admin-service-abac.md),
[Story 06 — Keycloak Realm Setup](06-keycloak-realm-setup.md)

## Goal

Build a service that is **both** an OAuth2 resource server (for inbound user requests) **and** an
OAuth2 client (for one outbound call to `user-service`) — the two concerns configured
independently, on purpose, so it's obvious in the code which credentials authorize which
direction of traffic.

**If "client-credentials grant" doesn't already mean something concrete to you, read
[`CONCEPTS.md` §2](CONCEPTS.md#2-oauth2-and-oidc-the-grants-this-project-actually-uses) first** —
this story is entirely about a service authenticating as *itself*, which is a different flow from
the human-login token you validated in Story 03, and the distinction matters for everything below.

## Context

Base package: `com.enterprise.security.orderservice`. This is the story where the system stops
being a set of isolated services: `order-service` needs to confirm an order's owner is a real,
active user before persisting it, and it does that by calling `user-service`'s `/internal` API
from Story 03 — not with the caller's own token, but with a token `order-service` mints for
*itself*.

## Tasks

### Task 1 — Module dependencies

`order-service/build.gradle.kts`:

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
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.h2database:h2")
}
```

Two dependencies this module has that `user-service` didn't:
`spring-boot-starter-oauth2-client` (the client-credentials machinery) and
`spring-boot-starter-webflux` — pulled in **only** for its `WebClient`, used as a plain
synchronous-style HTTP client here (via `.block()`). This stays a servlet application throughout;
WebFlux's reactive server is never used. (`WebClient`'s OAuth2 client-credentials support predates
the newer blocking `RestClient`'s equivalent, which needs Spring Security 6.3.4+ — one patch ahead
of what the Boot 3.3.4 BOM resolves — so `WebClient` is the correct choice here, not a shortcut.)

### Task 2 — `domain/Order.java` and `domain/OrderStatus.java`

```java
public enum OrderStatus {
    CREATED,
    CONFIRMED,
    CANCELLED
}
```

```java
@Entity
@Table(name = "customer_order")   // "order" is a reserved SQL keyword — don't call the table that
public class Order {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    /** Keycloak subject of the user who placed the order — used for ownership checks. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(nullable = false)
    private String item;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Order() { }

    public Order(String id, String ownerId, String tenantId, String item, int quantity, OrderStatus status) {
        this.id = id;
        this.ownerId = ownerId;
        this.tenantId = tenantId;
        this.item = item;
        this.quantity = quantity;
        this.status = status;
    }

    // getters for all fields; only status has a setter
}
```

### Task 3 — Repository and DTOs

`repository/OrderRepository.java`:
```java
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByOwnerId(String ownerId);
    List<Order> findByTenantId(String tenantId);
}
```

`dto/CreateOrderRequest.java` — record: `@NotBlank String item`, `@Min(1) int quantity`.

`dto/OrderResponse.java` — record mirroring `Order`'s fields (`id, ownerId, tenantId, item,
quantity, status, createdAt`) with a static `from(Order)` factory, same pattern as
`UserResponse` in Story 03.

`service/OrderNotFoundException.java` — same shape as `UserNotFoundException`:
`extends RuntimeException`, message `"Order not found: " + id`.

`client/RemoteUser.java` — record `(String id, String tenantId, boolean active)`. This is a
**deliberately separate, local** projection of the fields `order-service` needs from
`user-service`'s user data — it does not import or depend on `user-service`'s `UserResponse` type.
Two independently-deployable services never share a DTO class; each owns its own view of the
data it consumes across the wire.

### Task 4 — The outbound side: calling `user-service` with its own credentials

`config/OAuth2ClientConfig.java`:
```java
@Configuration
public class OAuth2ClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
    }
}
```
`.clientCredentials()` and nothing else in the provider builder — this service never does
authorization-code or password grants; it only ever authenticates as *itself*.
`AuthorizedClientServiceOAuth2AuthorizedClientManager` (not the request-scoped
`OAuth2AuthorizedClientManager` variant used in browser-facing apps) caches the authorized client
per client *registration*, not per user session — appropriate for a backend service with no
concept of a logged-in user session of its own.

`client/UserServiceClient.java`:
```java
@Component
public class UserServiceClient {

    private static final String REGISTRATION_ID = "user-service-client";

    private final WebClient webClient;

    public UserServiceClient(WebClient.Builder webClientBuilder,
                              OAuth2AuthorizedClientManager authorizedClientManager,
                              Environment env) {
        ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2Filter =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2Filter.setDefaultClientRegistrationId(REGISTRATION_ID);

        this.webClient = webClientBuilder
                .baseUrl(env.getProperty("services.user-service.base-url", "http://localhost:8081"))
                .apply(oauth2Filter.oauth2Configuration())
                .build();
    }

    public RemoteUser getUser(String userId) {
        return webClient.get()
                .uri("/internal/users/{id}", userId)
                .retrieve()
                .bodyToMono(RemoteUser.class)
                .block();
    }
}
```
`REGISTRATION_ID = "user-service-client"` must exactly match the registration name you'll add to
`application.yml` in Task 7 — a mismatch here fails silently at request time with a confusing
"no ClientRegistration" error, not a compile error, so double check the string matches.

### Task 5 — `OrderService`: the business logic + inbound method security

```java
@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserServiceClient userServiceClient;

    public OrderService(OrderRepository orderRepository, UserServiceClient userServiceClient) {
        this.orderRepository = orderRepository;
        this.userServiceClient = userServiceClient;
    }

    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    @Audited(action = "ORDER_CREATE")
    public Order createOrder(CreateOrderRequest request, String ownerId) {
        RemoteUser owner = userServiceClient.getUser(ownerId);
        if (!owner.active()) {
            throw new IllegalStateException("User is not active: " + ownerId);
        }
        Order order = new Order(UUID.randomUUID().toString(), ownerId, owner.tenantId(),
                request.item(), request.quantity(), OrderStatus.CREATED);
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN') or hasRole('SUPPORT') or returnObject.ownerId == authentication.name")
    public Order getOrder(String id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Order> listMyOrders(String ownerId) {
        return orderRepository.findByOwnerId(ownerId);
    }

    @Transactional(readOnly = true)
    @Secured({"ROLE_ADMIN", "ROLE_SUPPORT"})
    public List<Order> listOrdersInCurrentTenant() {
        return orderRepository.findByTenantId(TenantContext.get());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPPORT')")
    @Audited(action = "ORDER_CANCEL")
    public Order cancelOrder(String id) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }
}
```

Note what `createOrder` does with the response from `user-service`: it takes the order's
`tenantId` from `owner.tenantId()` (the *authoritative* value from the owning service), never
trusting a tenant claimed by the caller directly — and it rejects the whole operation if
`owner.active()` is false, all *before* touching the database. `listMyOrders` intentionally has no
`@PreAuthorize` — like `user-service`'s `/me`, it only ever returns the caller's own data by
construction (queries by the caller's own subject), so there's nothing to authorize.

### Task 6 — Controller and exception handling

`web/OrderController.java` — `@RestController @RequestMapping("/api/orders")`:
- `POST` (root) → `@Valid @RequestBody CreateOrderRequest`, `@AuthenticationPrincipal Jwt jwt`,
  returns `201 CREATED` with `OrderResponse.from(orderService.createOrder(request, jwt.getSubject()))`
- `GET /{id}` → `OrderResponse.from(orderService.getOrder(id))`
- `GET /mine` → `@AuthenticationPrincipal Jwt jwt`, lists `orderService.listMyOrders(jwt.getSubject())`
- `GET /tenant` → lists `orderService.listOrdersInCurrentTenant()`
- `POST /{id}/cancel` → `OrderResponse.from(orderService.cancelOrder(id))`

`web/OrderExceptionHandler.java` — `@RestControllerAdvice` with two handlers:
`OrderNotFoundException` → 404 (same pattern as Story 03), and **`IllegalStateException` → 422
Unprocessable Entity**, `title="Order Rejected"` — this is what turns `createOrder`'s "user is not
active" rejection into a proper HTTP response instead of a 500.

### Task 7 — `config/SecurityConfig` and `application.yml`

`SecurityConfig` is structurally identical to `user-service`'s (Story 03, Task 6) — same
`csrf().disable()`, `STATELESS`, headers, `oauth2ResourceServer().jwt(...)`, exception handling,
and `TenantContextFilter` placement — with one route difference: no `/internal/**` rule (this
service exposes no internal-only API of its own), just
`.requestMatchers("/actuator/health", "/actuator/info").permitAll().anyRequest().authenticated()`.
The class-level comment worth keeping: *"order-service is a resource server for inbound user
requests and, separately, an OAuth2 client for outbound calls to user-service — the two concerns
don't overlap on the same filter chain."*

`application.yml`:
```yaml
server:
  port: 8082

spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:order_service}
    username: ${DB_USERNAME:order_service}
    password: ${DB_PASSWORD:order_service}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  security:
    oauth2:
      client:
        registration:
          user-service-client:
            provider: keycloak
            client-id: order-service
            client-secret: ${ORDER_SERVICE_CLIENT_SECRET:order-service-secret}
            authorization-grant-type: client_credentials
            scope: internal
        provider:
          keycloak:
            token-uri: ${KEYCLOAK_TOKEN_URI:http://localhost:8080/realms/enterprise/protocol/openid-connect/token}
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8080/realms/enterprise}

security:
  resource:
    client-id: order-service

services:
  user-service:
    base-url: ${USER_SERVICE_BASE_URL:http://localhost:8081}

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

The registration key `user-service-client` under `spring.security.oauth2.client.registration`
must match `UserServiceClient.REGISTRATION_ID` from Task 4 exactly. `client-secret` here is a
demo-only plaintext default (`order-service-secret`) — Story 06 will create a matching Keycloak
client with that same secret; Story 07/08's "known simplifications" call out why this isn't
production-appropriate.

Add `OrderServiceApplication.java` — same trivial `@SpringBootApplication` shape as Story 03.

### Task 8 — Tests

`src/test/resources/application-test.yml` — same H2 + lazy `jwk-set-uri` pattern as Story 03
(no OAuth2 client config needed here since the client call gets mocked out, per below).

`OrderServiceApplicationTests` — same `contextLoads()` smoke test as Story 03.

`web/OrderControllerSecurityTest` — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`.
The one new technique versus Story 03: **`@MockBean private UserServiceClient
userServiceClient;`** — replace the real client with a Mockito mock so these tests never make a
live network call. In `@BeforeEach`, clear the repository and stub the default response:
`when(userServiceClient.getUser(anyString())).thenReturn(new RemoteUser("owner-1", "tenant-a",
true));`. A comment worth keeping on the class: *"UserServiceClient is mocked here rather than
exercised for real: it does a live OAuth2 client-credentials + HTTP round trip to user-service,
which belongs in a dedicated Testcontainers-based integration test, not a fast unit-style MockMvc
test of order-service's own authorization rules."* (That Testcontainers-based integration test is
explicitly out of scope for this project — the manual curl walkthrough in Story 07 is what
exercises the real end-to-end call.)

Cases to write:

| Scenario | Expected status |
|---|---|
| Anonymous `GET /api/orders/mine` | 401 |
| USER creates an order for themselves (mocked owner active=true) | 201 |
| Creating an order when the mocked owner is `active=false` | 422 |
| Owner reads their own order | 200 |
| A different subject reads that order | 403 |
| Plain USER cancels an order | 403 |
| ADMIN cancels an order | 200 |

## Definition of done

- [ ] `./gradlew :order-service:test` passes all cases above
- [ ] `./gradlew :order-service:bootJar` succeeds
- [ ] `UserServiceClient.REGISTRATION_ID` matches the `application.yml` registration key exactly
- [ ] `createOrder` rejects inactive owners with a 422, not a 500 or a silently-created order

## What NOT to do yet

Don't try to run this against a *real* `user-service` yet — that needs Keycloak issuing a real
client-credentials token, which is [Story 06](06-keycloak-realm-setup.md), exercised end-to-end in
[Story 07](07-docker-compose-local-dev.md). For now, "done" means the mocked-client test suite is
green.
