# Story 05 — `admin-service`: Custom `PermissionEvaluator` (ABAC) & API-Key Auth

**Depends on:** [Story 02 — common-security Library](02-common-security-library.md)
(independent of Stories 03/04 otherwise — build it any time after Story 02)
**Unlocks:** [Story 06 — Keycloak Realm Setup](06-keycloak-realm-setup.md)

## Goal

Build the service that goes beyond RBAC: a custom `PermissionEvaluator` enforcing tenant isolation
and clearance-vs-classification checks *ahead of* any role check, plus a second, non-JWT
authentication scheme (API key) coexisting on the same filter chain as the JWT-based one.

## Context

Base package: `com.enterprise.security.adminservice`. Every other service in this project uses
RBAC (and ownership) exclusively. This one owns `Document`, the one resource where "does the
caller have role X" isn't a sufficient question — you also need "is the caller even allowed to see
resources in this document's tenant, and does their clearance cover this document's
classification." That's attribute-based access control (ABAC), and it's implemented once, in one
class, layered *underneath* `@PreAuthorize` rather than replacing it.

**If RBAC and ABAC aren't already clearly distinct in your head, read
[`CONCEPTS.md` §3](CONCEPTS.md#3-rbac-vs-abac) before Task 4** — the whole point of this story is
a check that RBAC alone can't express, and the ordering inside `checkAccess` (tenant, then
clearance, then role) only makes sense once you see why role-based checks aren't enough here.

## Tasks

### Task 1 — Module dependencies

`admin-service/build.gradle.kts` — identical shape to `user-service`'s (Story 03, Task 1); no
OAuth2 client, no WebFlux (this service makes no outbound service-to-service calls):

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

### Task 2 — `domain/Classification.java` and `domain/Document.java`

```java
/**
 * Ordinal doubles as the required clearance level (PUBLIC=0 .. RESTRICTED=3), compared against
 * the caller's "clearance" JWT claim in DocumentPermissionEvaluator.
 */
public enum Classification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
```

The ordinal-as-clearance-level trick is deliberate and load-bearing — don't reorder these
constants later without checking every place that compares against `.ordinal()`.

```java
@Entity
@Table(name = "document")
public class Document {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Classification classification;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Document() { }

    public Document(String id, String tenantId, String ownerId, Classification classification, String title, String body) {
        this.id = id;
        this.tenantId = tenantId;
        this.ownerId = ownerId;
        this.classification = classification;
        this.title = title;
        this.body = body;
    }

    // getters for all fields; setters only for title, body
}
```

### Task 3 — Repository and DTOs

`repository/DocumentRepository.java`:
```java
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByTenantId(String tenantId);
    List<Document> findByClassification(Classification classification);
}
```

`dto/CreateDocumentRequest.java` — record: `@NotBlank String title`, `@NotBlank String body`,
`@NotNull Classification classification`.

`dto/UpdateDocumentRequest.java` — record: `@NotBlank String title`, `@NotBlank String body`
(classification and tenant are immutable after creation — there's no field for them here).

`dto/DocumentResponse.java` — record mirroring `Document` (`id, tenantId, ownerId,
classification, title, body, createdAt`) with a static `from(Document)` factory.

`service/DocumentNotFoundException.java` — same shape as the other services' not-found
exceptions: `"Document not found: " + id`.

### Task 4 — `DocumentPermissionEvaluator`: the ABAC core of this story

This is the single most important class in the whole project to get exactly right — build it
precisely as follows, and understand *why* before moving on:

```java
@Component
public class DocumentPermissionEvaluator implements PermissionEvaluator {

    private final DocumentRepository documentRepository;

    public DocumentPermissionEvaluator(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (!(targetDomainObject instanceof Document document)) {
            return false;
        }
        return checkAccess(authentication, document, permission.toString());
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (!"Document".equals(targetType) || targetId == null) {
            return false;
        }
        return documentRepository.findById(targetId.toString())
                .map(document -> checkAccess(authentication, document, permission.toString()))
                .orElse(false);
    }

    private boolean checkAccess(Authentication authentication, Document document, String permission) {
        if (hasRole(authentication, "PLATFORM_ADMIN")) {
            return true;
        }
        if (!document.getTenantId().equals(tenantClaim(authentication))) {
            return false;
        }
        if (clearanceClaim(authentication) < document.getClassification().ordinal()) {
            return false;
        }
        if ("WRITE".equalsIgnoreCase(permission)) {
            return hasRole(authentication, "ADMIN")
                    || hasRole(authentication, "EDITOR")
                    || document.getOwnerId().equals(authentication.getName());
        }
        return true;
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(("ROLE_" + role)::equals);
    }

    private String tenantClaim(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("tenant") : null;
    }

    private int clearanceClaim(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return 0;
        }
        Object clearance = jwt.getClaim("clearance");
        return clearance instanceof Number number ? number.intValue() : 0;
    }
}
```

Walk through `checkAccess` in this exact order — the order is the whole point:

1. **`ROLE_PLATFORM_ADMIN` first, unconditionally.** This is the *one deliberate* cross-tenant
   escape hatch in the entire system. Every other branch below is bypassed for this role, on
   purpose — modelling a platform-operations role that legitimately spans tenants.
2. **Tenant isolation, before anything else.** `document.getTenantId().equals(tenantClaim(...))`
   — if this fails, deny immediately. Not even an `ADMIN` role from a *different* tenant gets past
   this line. Get this line wrong (e.g. compare the wrong field, or skip it for `ADMIN`) and you've
   built a cross-tenant data leak.
3. **Clearance vs. classification, next.** `clearanceClaim(authentication) <
   document.getClassification().ordinal()` — denies before any role is even inspected. A `USER`
   with clearance 3 and an `ADMIN` with clearance 0 get exactly the same outcome against a
   `RESTRICTED` document: denied for the admin, allowed for the user, if that's what their
   clearance says. Role and clearance are independent axes.
4. **Only then, if `permission` is `"WRITE"`,** does role/ownership matter at all: `ADMIN` or
   `EDITOR` role, or the caller being `document.getOwnerId()`. A `READ` request that reaches this
   point (survived platform-admin check, tenant check, clearance check) is simply allowed — there
   is no additional read-specific role gate.
5. **Every denial returns `false`, never throws, never distinguishes "not found" from "not
   allowed."** The two-argument overload (`hasPermission(authentication, targetId, targetType,
   permission)`, used by `@PreAuthorize("hasPermission(#id, 'Document', 'READ')")`) maps a missing
   document straight to `false` via `.orElse(false)` — a 403 for a real document in another
   tenant and a 403 for a document that doesn't exist at all must be indistinguishable from the
   caller's side, or the API becomes an oracle for probing which IDs exist.

### Task 5 — `MethodSecurityConfig`: wiring the evaluator into `@PreAuthorize`

```java
@Configuration
public class MethodSecurityConfig {

    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(DocumentPermissionEvaluator permissionEvaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(permissionEvaluator);
        return handler;
    }
}
```

The `@Bean` method **must be `static`**. Method-security interceptors are constructed very early
in the application context lifecycle, as `BeanPostProcessor`s — a non-static `@Bean` method here
(which would require an instance of `MethodSecurityConfig` to be constructed first) triggers a
"bean X is currently in creation" cycle at startup. This is Spring Security's own documented
requirement for this specific bean, not a stylistic choice — don't drop the `static`.

### Task 6 — `DocumentService`

```java
@Service
@Transactional
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('EDITOR')")
    @Audited(action = "DOCUMENT_CREATE")
    public Document createDocument(CreateDocumentRequest request, String ownerId, String tenantId) {
        Document document = new Document(UUID.randomUUID().toString(), tenantId, ownerId,
                request.classification(), request.title(), request.body());
        return documentRepository.save(document);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'Document', 'READ')")
    public Document getDocument(String id) {
        return documentRepository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @PreAuthorize("hasPermission(#id, 'Document', 'WRITE')")
    @Audited(action = "DOCUMENT_UPDATE")
    public Document updateDocument(String id, UpdateDocumentRequest request) {
        Document document = documentRepository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        document.setTitle(request.title());
        document.setBody(request.body());
        return documentRepository.save(document);
    }

    @PreAuthorize("hasPermission(#id, 'Document', 'WRITE')")
    @Audited(action = "DOCUMENT_DELETE")
    public void deleteDocument(String id) {
        if (!documentRepository.existsById(id)) {
            throw new DocumentNotFoundException(id);
        }
        documentRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    @Secured({"ROLE_ADMIN", "ROLE_SUPPORT", "ROLE_EDITOR"})
    public List<Document> listDocumentsInCurrentTenant() {
        return documentRepository.findByTenantId(TenantContext.get());
    }

    /** Backing the API-key-authenticated partner feed: only ever the PUBLIC classification. */
    @Transactional(readOnly = true)
    public List<Document> listPublicDocuments() {
        return documentRepository.findByClassification(Classification.PUBLIC);
    }
}
```

Two things worth noticing: `createDocument` is a plain RBAC gate (`ADMIN` or `EDITOR`) — ABAC only
governs *reading and writing existing* documents, not creation, since a new document has no
tenant/classification to compare against until it's constructed. And `listDocumentsInCurrentTenant`
combines a coarse `@Secured` role gate with the query itself being scoped to
`TenantContext.get()` — bulk listing applies tenant isolation structurally (via the `WHERE`
clause) rather than by filtering a full table scan through the per-row evaluator.

### Task 7 — Controllers

`web/DocumentController.java` — `@RestController @RequestMapping("/api/documents")`:
- `POST` (root) → `@Valid @RequestBody CreateDocumentRequest`, `@AuthenticationPrincipal Jwt jwt`,
  returns `201 CREATED` with
  `DocumentResponse.from(documentService.createDocument(request, jwt.getSubject(), jwt.getClaimAsString("tenant")))`
  — note the tenant comes from the **caller's own JWT claim**, not from the request body; a
  client can never create a document in a tenant other than its own.
- `GET /{id}`, `PUT /{id}`, `DELETE /{id}`, `GET` (list) — same pattern as the other services'
  controllers, delegating straight to the matching `DocumentService` method.

`web/PartnerDocumentController.java` — `@RestController @RequestMapping("/partner/documents")`,
one endpoint:
```java
@GetMapping("/public")
@PreAuthorize("hasRole('PARTNER')")
public List<DocumentResponse> listPublicDocuments() {
    return documentService.listPublicDocuments().stream().map(DocumentResponse::from).toList();
}
```
The method-level `@PreAuthorize` here backs up the route-level rule you'll add in Task 8 — this
controller is the external-partner surface, authenticated via `X-API-Key` rather than a Keycloak
JWT (see Task 8), modelling a legacy/B2B integration that can't do OAuth2.

`web/DocumentExceptionHandler.java` — `@RestControllerAdvice`, one handler for
`DocumentNotFoundException` → 404, same pattern as the other services.

### Task 8 — `SecurityConfig`: two credential types, one filter chain

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            KeycloakRoleConverter keycloakRoleConverter,
                                            TenantContextFilter tenantContextFilter,
                                            ApiKeyAuthFilter apiKeyAuthFilter,
                                            RestAuthenticationEntryPoint authenticationEntryPoint,
                                            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(SecurityHeadersCustomizer.apiDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/partner/**").hasRole("PARTNER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(apiKeyAuthFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}
```

The one structural difference from Stories 03/04: `.addFilterBefore(apiKeyAuthFilter,
BearerTokenAuthenticationFilter.class)`. Because `ApiKeyAuthFilter` (Story 02) only sets an
`Authentication` when the `X-API-Key` header is actually present and only calls
`filterChain.doFilter(...)` regardless, ordering it *before* the bearer-token filter means: a
request with a valid API key is already authenticated by the time the JWT filter runs (which then
does nothing, since an `Authentication` is already set), and a request with no API key falls
through untouched to normal JWT processing. Both schemes coexist without either interfering with
the other.

### Task 9 — `application.yml`

```yaml
server:
  port: 8083

spring:
  application:
    name: admin-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:admin_service}
    username: ${DB_USERNAME:admin_service}
    password: ${DB_PASSWORD:admin_service}
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
    client-id: admin-service
  # Demo-only plaintext registry — see ApiKeyProperties for the production caveat.
  api-keys:
    partner-acme:
      key: ${PARTNER_ACME_API_KEY:demo-partner-acme-key}
      authorities:
        - ROLE_PARTNER

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

`security.api-keys.partner-acme` binds to `ApiKeyProperties` (Story 02) — this is the **only**
place `ROLE_PARTNER` is ever granted; it never appears in Keycloak at all (confirm that against
Story 06's realm export once you get there).

Add `AdminServiceApplication.java` — same trivial `@SpringBootApplication` shape as the other two
services.

### Task 10 — Tests (three files, three different techniques)

`src/test/resources/application-test.yml` — H2 + lazy `jwk-set-uri`, same as the other services,
**plus** a test-only API key:
```yaml
security:
  api-keys:
    partner-acme:
      key: test-partner-key
      authorities:
        - ROLE_PARTNER
```

`AdminServiceApplicationTests` — the same trivial `@SpringBootTest @ActiveProfiles("test")`
`contextLoads()` smoke test as Stories 03/04.

**`security/DocumentPermissionEvaluatorTest`** — a fast, Spring-context-free unit test using
`@ExtendWith(MockitoExtension.class)` and `@Mock DocumentRepository`. Construct
`JwtAuthenticationToken`s directly (no MockMvc, no HTTP) via a small local `token(subject, tenant,
clearance, roles...)` helper that builds a `Jwt` with `tenant`/`clearance` claims and wraps it in a
`JwtAuthenticationToken` with the given `ROLE_*` authorities. Cover, at minimum:
- an `ADMIN` from tenant-a is denied on a tenant-b document, even though they're an admin
- a `PLATFORM_ADMIN` is allowed on that same cross-tenant document
- clearance 0 is denied `READ` on a `CONFIDENTIAL` document; clearance 2 is allowed
- a same-tenant `USER` who isn't the owner is denied `WRITE`; the owner themselves is allowed
- a lookup by a missing document id returns `false`, not an exception

**`web/DocumentControllerSecurityTest`** — `@SpringBootTest @AutoConfigureMockMvc
@ActiveProfiles("test")`, exercising the *whole chain* through real HTTP requests (route rules +
`@PreAuthorize` + the evaluator together, not the evaluator in isolation — that's what the unit
test above is for). Build documents through the real `POST /api/documents` endpoint inside each
test (parse the `id` back out of the JSON response) rather than inserting directly via the
repository, so each test also exercises `createDocument`'s own authorization. Cover: anonymous →
401; `EDITOR` creates in their own tenant → 201; plain `USER` creating → 403; an `ADMIN` from a
different tenant reading a tenant-a document → 403 (tenant isolation beats role); a
`PLATFORM_ADMIN` reading across tenants → 200; a low-clearance same-tenant user reading a
`RESTRICTED` document → 403.

**`web/PartnerDocumentControllerSecurityTest`** — no `jwt()` post-processor at all; instead set the
`X-API-Key` header directly on the request. Cover: no header → 401; wrong key → 401 (not 403 — a
bad/missing credential is an authentication failure, handled by
`RestAuthenticationEntryPoint`, not an authorization failure); the correct test key
(`test-partner-key`, matching `application-test.yml`) → 200.

## Definition of done

- [ ] `./gradlew :admin-service:test` passes across all three test files
- [ ] `./gradlew :admin-service:bootJar` succeeds
- [ ] `DocumentPermissionEvaluator.checkAccess` implements the exact check order from Task 4 —
      platform-admin, then tenant, then clearance, then write-permission role/ownership
- [ ] `MethodSecurityExpressionHandler`'s `@Bean` method is `static`
- [ ] `ROLE_PARTNER` appears nowhere except `application.yml`'s `security.api-keys` config

## What NOT to do yet

No Keycloak realm yet (that's [Story 06](06-keycloak-realm-setup.md)) — the JWT-based tests here
run entirely against mocked tokens via `SecurityMockMvcRequestPostProcessors.jwt()`, never a real
issuer.
