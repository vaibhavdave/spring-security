# Story 02 — `common-security`: Shared Security Library

**Depends on:** [Story 01 — Gradle Multi-Module Scaffold](01-gradle-multi-module-scaffold.md)
**Unlocks:** [Story 03 — user-service](03-user-service-rbac.md),
[Story 04 — order-service](04-order-service-oauth2-client.md),
[Story 05 — admin-service](05-admin-service-abac.md) (all three depend on this module)

## Goal

Build the one shared library every resource service will depend on: JWT role mapping, consistent
RFC 7807 error responses, tenant-context propagation, API-key authentication, audit logging, and
hardened security headers — packaged as a Spring Boot auto-configuration so each service pulls it
in with a single dependency line and gets every bean for free.

## Context

This module is a plain Java library (`java-library` plugin, not `org.springframework.boot`) — it
produces a normal jar, not an executable one. Every class here is infrastructure; none of it knows
about `User`, `Order`, or `Document` — that separation is what lets three different services reuse
it unmodified. Base package: `com.enterprise.security.common`.

Everything in this story gets wired together by **one** auto-configuration class at the end
(Task 8) — build the pieces first, they won't do anything on their own until that class registers
them as beans.

## Tasks

### Task 1 — Module dependencies

`common-security/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-aop")
    api("org.springframework.boot:spring-boot-autoconfigure")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

Note `api` (not `implementation`) for every dependency: consuming services need these types
(`Jwt`, `SecurityFilterChain`, etc.) on their own compile classpath, not just at runtime.

### Task 2 — JWT role mapping (`jwt` package)

**`JwtRoleMappingProperties`** — `@ConfigurationProperties(prefix = "security.resource")`, one
field: `String clientId` (getter/setter), default `""`. This is how each service tells the
converter which Keycloak client's `resource_access` roles belong to it.

**`KeycloakRoleConverter`** implements `Converter<Jwt, AbstractAuthenticationToken>`. This is the
most safety-critical class in the module — every authorization decision downstream depends on it
producing the right `GrantedAuthority` set. Build it exactly as follows:

```java
package com.enterprise.security.common.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.*;
import java.util.stream.Collectors;

public class KeycloakRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String resourceClientId;
    private final JwtGrantedAuthoritiesConverter scopeAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    private final JwtAuthenticationConverter delegate;

    public KeycloakRoleConverter(String resourceClientId) {
        this.resourceClientId = resourceClientId;
        this.delegate = new JwtAuthenticationConverter();
        this.delegate.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopeAuthoritiesConverter.convert(jwt));
        authorities.addAll(realmRoles(jwt));
        authorities.addAll(resourceRoles(jwt));
        return authorities;
    }

    @SuppressWarnings("unchecked")
    private Set<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private Set<GrantedAuthority> resourceRoles(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess == null || !(resourceAccess.get(resourceClientId) instanceof Map<?, ?> clientAccess)) {
            return Set.of();
        }
        Object roles = clientAccess.get("roles");
        if (!(roles instanceof List<?> roleList)) {
            return Set.of();
        }
        return roleList.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toSet());
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt source) {
        return delegate.convert(source);
    }
}
```

Three things to get right, in order of how badly they'd break authorization if wrong:
1. Roles get the `ROLE_` prefix added — `hasRole('ADMIN')` checks for authority `ROLE_ADMIN`, and
   Keycloak's token only contains the bare role name `ADMIN`.
2. `resourceRoles` only reads `resource_access.<resourceClientId>` — **not** every client in the
   token. A token can legitimately carry roles for other clients; leaking those into this
   service's authorities would be a privilege-escalation bug.
3. Every branch tolerates a missing claim (`realm_access` absent, `roles` absent/wrong type) by
   returning `Set.of()` rather than throwing — a token that simply has no roles must not crash
   authentication.

### Task 3 — RFC 7807 error responses (`error` package)

Three classes, all producing `application/problem+json` instead of Spring's default HTML/blank
error pages:

- **`RestAuthenticationEntryPoint`** implements `AuthenticationEntryPoint` — handles the *no valid
  token at all* case (401). Constructor takes an `ObjectMapper`. Builds a `ProblemDetail` via
  `ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED)`, sets `title="Unauthorized"`,
  `detail="A valid bearer token is required to access this resource."`,
  `type=URI.create("https://enterprise-security.example.com/errors/unauthorized")`, and
  `instance=URI.create(request.getRequestURI())`. Writes it with
  `response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE)` then
  `objectMapper.writeValue(response.getWriter(), problem)`.
- **`RestAccessDeniedHandler`** implements `AccessDeniedHandler` — handles the *authenticated but
  not permitted* case (403). Same shape: `title="Forbidden"`,
  `detail="You do not have permission to perform this action."`,
  `type=.../errors/forbidden`.
- **`GlobalExceptionHandler`** — `@RestControllerAdvice` catching exceptions thrown *inside*
  controller/service methods (as opposed to the two handlers above, which run at the filter-chain
  level before a controller is ever reached):
  - `@ExceptionHandler(AccessDeniedException.class)` → same 403 `ProblemDetail` shape as above
    (this catches denials from method security, e.g. a failed `@PreAuthorize`, which throw rather
    than going through the filter chain)
  - `@ExceptionHandler(AuthenticationException.class)` → same 401 shape
  - `@ExceptionHandler(MethodArgumentNotValidException.class)` → 400, `title="Validation Failed"`,
    `detail` = each field error as `"field: message"` joined with `"; "`
  - `@ExceptionHandler(Exception.class)` → 500, logs the exception at `error` level first
    (`private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`),
    generic `detail="An unexpected error occurred."` — **never** include `ex.getMessage()` here;
    leaking internal exception text in a 500 body is an information-disclosure bug.

### Task 4 — Tenant propagation (`tenant` package)

**`TenantContext`** — a `final` utility class wrapping a `private static final ThreadLocal<String>`,
with static `set(String)`, `get()`, `clear()` methods and a private constructor.

**`TenantContextFilter`** extends `OncePerRequestFilter`. In `doFilterInternal`: read the current
`Authentication` from `SecurityContextHolder`; if its principal is a `Jwt`, read the `"tenant"`
claim via `jwt.getClaimAsString("tenant")` and call `TenantContext.set(tenant)` if non-null; then
`filterChain.doFilter(request, response)`; and in a `finally` block, **always** call
`TenantContext.clear()`. The `finally` clear is not optional — servlet containers reuse threads
across requests, so skipping it would leak one request's tenant into the next request handled by
that thread.

### Task 5 — API-key authentication (`apikey` package)

**`ApiKeyProperties`** — `@ConfigurationProperties(prefix = "security")`, one field
`Map<String, Entry> apiKeys = Map.of()`, with a static nested `Entry` class holding
`String key` and `List<String> authorities = List.of()` (both with getters/setters). This binds
config like `security.api-keys.partner-acme.key` / `security.api-keys.partner-acme.authorities`.

**`ApiKeyAuthenticationToken`** extends `AbstractAuthenticationToken` — constructor
`(String principal, String apiKey, Collection<? extends GrantedAuthority> authorities)` calls
`super(authorities)` then `setAuthenticated(true)`; `getPrincipal()` returns the principal string,
`getCredentials()` returns the api key string.

**`ApiKeyAuthFilter`** extends `OncePerRequestFilter`. Constructor takes `ApiKeyProperties` and
stores `properties.getApiKeys()`. In `doFilterInternal`:
1. Read header `X-API-Key` (constant `HEADER_NAME = "X-API-Key"`).
2. If present, find the registry entry whose key **constant-time-equals** the presented value —
   use `java.security.MessageDigest.isEqual(expected.getBytes(), actual.getBytes())`, never `.equals()`.
   A naive `String.equals` short-circuits on the first mismatched byte, which makes response timing
   leak how many leading characters of the guess were correct — a real (if slow) side channel for
   brute-forcing the key.
3. On a match, build authorities from the entry's `authorities` list (`SimpleGrantedAuthority`
   each) and call `SecurityContextHolder.getContext().setAuthentication(new
   ApiKeyAuthenticationToken(partnerId, entry.getKey(), authorities))`.
4. Always call `filterChain.doFilter(request, response)` at the end — **whether or not** the
   header was present or matched. A request with no key, or a bad key, must fall through to normal
   JWT bearer-token authentication rather than being rejected here; that's what lets both schemes
   coexist on one filter chain in Story 05.

### Task 6 — Audit logging (`audit` package)

**`Audited`** — `@Retention(RUNTIME) @Target(METHOD)` annotation, one attribute
`String action() default ""`.

**`AuditLogAspect`** — plain `@Aspect` class (not a `@Component`; it gets registered as a bean
explicitly in Task 8, not picked up by component scanning, since this library has no component
scan of its own). One method:

```java
@Around("@annotation(audited)")
public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
```

Logic: resolve `action` (the annotation's `action()`, or the method name if blank), read the
current principal name from `SecurityContextHolder` (`"anonymous"` if none), read
`TenantContext.get()`. Put `auditAction`/`auditPrincipal`/`auditTenant` into SLF4J's `MDC`, call
`joinPoint.proceed()`. On success, log at `info` via a logger named `"AUDIT"` (i.e.
`LoggerFactory.getLogger("AUDIT")`, not the class name — a fixed logger name makes it trivial to
route audit lines to their own appender later) with `action`, `principal`, `tenant`,
`outcome=SUCCESS`, and a timestamp. On any `Exception`, log the same fields at `warn` with
`outcome=FAILURE` and `reason=<exception simple class name>`, then **rethrow** — this aspect
observes and logs, it never swallows an exception. Always remove the three MDC keys in a `finally`
block, for the same thread-reuse reason as `TenantContextFilter`.

### Task 7 — Security headers (`config` package, part 1)

**`SecurityHeadersCustomizer`** — a `final` utility class, private constructor, one static method
`Customizer<HeadersConfigurer<HttpSecurity>> apiDefaults()` returning a customizer that chains:
- `contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))`
  — safe because every consuming service is a JSON API, never an HTML page
- `frameOptions(FrameOptionsConfig::deny)`
- `referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))`
- `httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))`
- `permissionsPolicy(permissions -> permissions.policy("geolocation=(), camera=(), microphone=()"))`

This is a static helper, not a bean — each service's own `SecurityConfig` (Stories 03–05) calls
`.headers(SecurityHeadersCustomizer.apiDefaults())` directly.

### Task 8 — Wire it all together: `CommonSecurityAutoConfiguration`

```java
package com.enterprise.security.common.config;

import com.enterprise.security.common.apikey.ApiKeyAuthFilter;
import com.enterprise.security.common.apikey.ApiKeyProperties;
import com.enterprise.security.common.audit.AuditLogAspect;
import com.enterprise.security.common.error.GlobalExceptionHandler;
import com.enterprise.security.common.error.RestAccessDeniedHandler;
import com.enterprise.security.common.error.RestAuthenticationEntryPoint;
import com.enterprise.security.common.jwt.JwtRoleMappingProperties;
import com.enterprise.security.common.jwt.KeycloakRoleConverter;
import com.enterprise.security.common.tenant.TenantContextFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@AutoConfiguration
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@EnableConfigurationProperties({ApiKeyProperties.class, JwtRoleMappingProperties.class})
public class CommonSecurityAutoConfiguration {

    @Bean
    public KeycloakRoleConverter keycloakRoleConverter(JwtRoleMappingProperties props) {
        return new KeycloakRoleConverter(props.getClientId());
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }

    @Bean
    public TenantContextFilter tenantContextFilter() {
        return new TenantContextFilter();
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(ApiKeyProperties apiKeyProperties) {
        return new ApiKeyAuthFilter(apiKeyProperties);
    }

    @Bean
    public AuditLogAspect auditLogAspect() {
        return new AuditLogAspect();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
```

`@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)` belongs **here**, once, not
in each service — it's what makes `@PreAuthorize`/`@PostAuthorize`/`@Secured` work everywhere this
library is on the classpath (Stories 03–05 all rely on it being already on).

Register it as a Spring Boot auto-configuration so it activates automatically for any module that
depends on this one — create
`common-security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
containing exactly one line:

```
com.enterprise.security.common.config.CommonSecurityAutoConfiguration
```

(Older tutorials mention `spring.factories` for this — that mechanism still works but is
deprecated as of Spring Boot 2.7+; use the `AutoConfiguration.imports` file, which is what Boot
3.3.4 expects.)

### Task 9 — Unit test the highest-risk class

Everything else in this module either has no branching logic worth a dedicated unit test (the
error handlers, the headers customizer) or is better tested through a running service's MockMvc
tests (Stories 03–05 exercise `TenantContextFilter`, `ApiKeyAuthFilter`, and the audit aspect
indirectly through real requests). `KeycloakRoleConverter` is the exception: it's pure logic with
no Spring context needed, and a bug in it silently breaks authorization everywhere, so it gets its
own fast unit test.

Create `common-security/src/test/java/com/enterprise/security/common/jwt/KeycloakRoleConverterTest.java`
with three cases:

1. `mapsRealmRolesToRoleAuthorities` — a JWT with `realm_access.roles = ["ADMIN", "USER"]`
   converts to authorities containing `ROLE_ADMIN` and `ROLE_USER`.
2. `mapsClientSpecificResourceRolesForTheConfiguredClientOnly` — construct the converter with
   `new KeycloakRoleConverter("order-service")`; a JWT with
   `resource_access.order-service.roles = ["SERVICE"]` **and**
   `resource_access.some-other-client.roles = ["SHOULD_NOT_APPEAR"]` converts to authorities that
   contain `ROLE_SERVICE` and do **not** contain `ROLE_SHOULD_NOT_APPEAR`. This is the test that
   would catch the privilege-escalation bug described in Task 2.
3. `toleratesMissingRoleClaims` — a JWT with no `realm_access`/`resource_access` claims at all
   converts to an empty authority set, without throwing.

Build the test JWTs with `Jwt.withTokenValue("token-value").header("alg", "none")
.subject("user-123").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
.claims(claims -> claims.putAll(extraClaims)).build()`. Use AssertJ (`assertThat(...)`, already on
the classpath via `spring-boot-starter-test`) with
`.extracting(Object::toString).contains(...)`/`.doesNotContain(...)` on `token.getAuthorities()`.

## Definition of done

- [ ] `./gradlew :common-security:build` succeeds
- [ ] `./gradlew :common-security:test` passes, including all three `KeycloakRoleConverterTest` cases
- [ ] `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` exists
      and names `CommonSecurityAutoConfiguration`
- [ ] No class in this module references `User`, `Order`, `Document`, or any other
      domain/business type — that would break the "one library, three unrelated services" design

## What NOT to do yet

Don't add a `SecurityFilterChain` bean here — route rules (`authorizeHttpRequests`) are
service-specific and belong in each service's own `SecurityConfig` (Stories 03–05), which will
*compose* the beans this story built (`keycloakRoleConverter`, `tenantContextFilter`,
`apiKeyAuthFilter`, the two error handlers) into their own chain.
