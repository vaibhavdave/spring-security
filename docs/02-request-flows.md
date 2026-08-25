# Request Flows

[← Docs index](README.md)

Four flows that exercise every authentication mechanism in the project. Each diagram is a real,
runnable example — the curl commands are in the [root README](../README.md#example-requests).

## 1. User login → read your own profile (OIDC/JWT)

The most common path: a human gets a token from Keycloak, then calls a resource server directly.

```mermaid
sequenceDiagram
    actor U as alice (browser/curl)
    participant KC as Keycloak
    participant US as user-service

    U->>KC: POST /realms/enterprise/protocol/openid-connect/token<br/>grant_type=password, client_id=web-app
    KC-->>U: JWT access_token<br/>(realm_access.roles, tenant, clearance claims)
    U->>US: GET /api/users/me<br/>Authorization: Bearer $TOKEN
    US->>KC: fetch JWKS (cached)
    US->>US: KeycloakRoleConverter maps roles → GrantedAuthority<br/>TenantContextFilter reads "tenant" claim
    US-->>U: 200 UserResponse (self)
```

**What to notice:** `getCurrentUser` needs no `@PreAuthorize` at all — it reads
`jwt.getSubject()` and looks up exactly that user, so there's no other user's data it could leak.

## 2. Cross-service call: create an order (client-credentials)

`order-service` never accepts `bob`'s token; it validates it as normal user traffic, then mints
its **own** token to call `user-service` — see
[`OAuth2ClientConfig`](../order-service/src/main/java/com/enterprise/security/orderservice/config/OAuth2ClientConfig.java)
and
[`UserServiceClient`](../order-service/src/main/java/com/enterprise/security/orderservice/client/UserServiceClient.java).

```mermaid
sequenceDiagram
    actor U as bob (USER role)
    participant OS as order-service
    participant KC as Keycloak
    participant US as user-service

    U->>OS: POST /api/orders<br/>Authorization: Bearer $BOB_TOKEN
    OS->>OS: SecurityFilterChain: authenticated + hasRole('USER') (method security)
    OS->>KC: client_credentials grant<br/>(client_id=order-service, its own secret)
    KC-->>OS: service token (ROLE_SERVICE, sub=service-account-order-service)
    OS->>US: GET /internal/users/{ownerId}<br/>Authorization: Bearer (service token)
    US->>US: route rule: hasAnyRole('SERVICE','ADMIN')<br/>method: hasRole('SERVICE') or hasRole('ADMIN')
    US-->>OS: 200 RemoteUser {active, tenantId, ...}
    OS->>OS: reject if inactive, otherwise persist Order
    OS-->>U: 201 OrderResponse
```

**What to notice:** three independent checks gate `/internal/users/{id}` — the route rule in
`user-service`'s `SecurityConfig`, the `@PreAuthorize` on
`getUserForServiceCall`, and (in the Kubernetes/Istio deployment) a mesh `AuthorizationPolicy`
that only lets `order-service`'s SPIFFE identity reach that path at all. See
[04 · Security Layers](04-security-layers.md).

## 3. ABAC document access (tenant + clearance, ahead of role)

`admin-service`'s `DocumentPermissionEvaluator` runs *before* the method body, as part of the
`@PreAuthorize("hasPermission(#id, 'Document', 'READ')")` SpEL expression.

```mermaid
sequenceDiagram
    actor D as dave (tenant-b, clearance 2)
    participant AS as admin-service
    participant PE as DocumentPermissionEvaluator
    participant DB as Postgres

    D->>AS: GET /api/documents/{id}<br/>Authorization: Bearer $DAVE_TOKEN
    AS->>PE: hasPermission(id, "Document", "READ")
    PE->>DB: findById(id)
    DB-->>PE: Document{tenantId=tenant-a, classification=CONFIDENTIAL, ...}
    PE->>PE: ROLE_PLATFORM_ADMIN? no<br/>document.tenantId == dave.tenant ("tenant-b")? no → deny
    PE-->>AS: false
    AS-->>D: 403 Forbidden (RFC 7807 ProblemDetail)
```

If dave instead requested a **tenant-b** document classified `RESTRICTED` while his own
`clearance` claim is `2`, the tenant check would pass but the clearance check
(`clearance < classification.ordinal()`) would still deny it — two independent ABAC conditions,
both enforced before any role is even considered. `ROLE_PLATFORM_ADMIN` is the one deliberate
escape hatch that skips both checks.

## 4. Partner integration (API key, no JWT at all)

Models a legacy/B2B caller that can't do OAuth2.

```mermaid
sequenceDiagram
    participant P as Partner system
    participant F as ApiKeyAuthFilter
    participant AS as admin-service

    P->>F: GET /partner/documents/public<br/>X-API-Key: demo-partner-acme-key
    F->>F: constant-time compare against configured partner registry
    F->>F: match → set Authentication (ROLE_PARTNER), no JWT parsing at all
    F->>AS: request continues down the same filter chain
    AS->>AS: route rule hasRole('PARTNER') + method @PreAuthorize("hasRole('PARTNER')")
    AS-->>P: 200 [Document] (PUBLIC classification only)
```

**What to notice:** `ApiKeyAuthFilter` only acts when the `X-API-Key` header is present, so JWT
bearer-token requests on the same service are completely unaffected — the two schemes coexist on
one filter chain (`admin-service`'s `SecurityConfig`).
