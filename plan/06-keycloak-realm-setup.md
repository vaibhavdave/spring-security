# Story 06 — Keycloak Realm & Local Identity Setup

**Depends on:** [Story 03](03-user-service-rbac.md), [Story 04](04-order-service-oauth2-client.md),
[Story 05](05-admin-service-abac.md) — all three services' `application.yml` files reference the
client IDs and issuer this story creates
**Unlocks:** [Story 07 — Docker Compose Local Dev Loop](07-docker-compose-local-dev.md)

## Goal

Define a Keycloak realm that issues JWTs carrying everything the three services need for
authorization: standard roles, and two **custom claims** (`tenant`, `clearance`) that don't exist
in Keycloak by default.

## Context

Every `application.yml` from Stories 03–05 already points at
`http://localhost:8080/realms/enterprise` and references specific client IDs/secrets
(`order-service` / `order-service-secret`, etc.) — this story is what makes those references real.
Rather than clicking through the Keycloak admin console by hand (error-prone and not reproducible),
this realm is defined as a single importable JSON file, `keycloak/realm-export.json`, that
Keycloak loads automatically on startup via `--import-realm` (wired up in Story 07).

## Tasks

### Task 1 — Realm-level settings

Create `keycloak/realm-export.json`. Start with the realm shell:

```json
{
  "realm": "enterprise",
  "enabled": true,
  "displayName": "Enterprise Security Demo",
  "sslRequired": "external",
  "registrationAllowed": false,
  "loginWithEmailAllowed": true,
  "accessTokenLifespan": 900,
  "ssoSessionIdleTimeout": 1800,
  "ssoSessionMaxLifespan": 36000,
  "revokeRefreshToken": true,
  "refreshTokenMaxReuse": 0,
```

`accessTokenLifespan: 900` (15 minutes) keeps tokens short-lived even in this demo realm.
`revokeRefreshToken` + `refreshTokenMaxReuse: 0` mean each refresh token can be used exactly once
— a reused refresh token is rejected, which is a standard refresh-token-theft mitigation worth
having even in a demo.

### Task 2 — Realm roles

Six flat realm roles — no composite/hierarchical roles, and no client-specific roles for any of
the three resource services (unlike the `web-app` client, they don't need their own token
audience beyond what the realm roles already express):

```json
  "roles": {
    "realm": [
      { "name": "ADMIN", "description": "Full administrative access within a tenant" },
      { "name": "USER", "description": "Standard authenticated end user" },
      { "name": "SUPPORT", "description": "Read-mostly back-office access within a tenant" },
      { "name": "EDITOR", "description": "May create/update content within a tenant" },
      { "name": "SERVICE", "description": "Assigned to service accounts calling internal service-to-service APIs" },
      { "name": "PLATFORM_ADMIN", "description": "Cross-tenant platform operations role (bypasses tenant isolation)" }
    ]
  },
```

Cross-check each of these against where it's consumed: `ADMIN`/`USER`/`SUPPORT`/`EDITOR` are the
role names every `@PreAuthorize`/`@Secured`/`hasRole(...)` in Stories 03–05 checks against;
`SERVICE` is what `order-service`'s service account (Task 5) carries and what
`getUserForServiceCall`'s `@PreAuthorize` in Story 03 checks for; `PLATFORM_ADMIN` is the one
role `DocumentPermissionEvaluator` (Story 05) treats as a cross-tenant escape hatch.

### Task 3 — The custom claims: `tenant` and `clearance`

Neither `tenant` nor `clearance` is a built-in Keycloak concept — they're **user attributes**
(arbitrary key/value pairs Keycloak lets you attach to any user) exposed into the token via a
**client scope** with two **protocol mappers**:

```json
  "clientScopes": [
    {
      "name": "enterprise-claims",
      "description": "Custom claims (tenant, clearance) used by the resource services for ABAC",
      "protocol": "openid-connect",
      "attributes": {
        "include.in.token.scope": "true",
        "display.on.consent.screen": "false"
      },
      "protocolMappers": [
        {
          "name": "tenant",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-attribute-mapper",
          "consentRequired": false,
          "config": {
            "userinfo.token.claim": "true",
            "user.attribute": "tenant",
            "id.token.claim": "true",
            "access.token.claim": "true",
            "claim.name": "tenant",
            "jsonType.label": "String"
          }
        },
        {
          "name": "clearance",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-attribute-mapper",
          "consentRequired": false,
          "config": {
            "userinfo.token.claim": "true",
            "user.attribute": "clearance",
            "id.token.claim": "true",
            "access.token.claim": "true",
            "claim.name": "clearance",
            "jsonType.label": "int"
          }
        }
      ]
    }
  ],
```

`"access.token.claim": "true"` on both mappers is the important flag — it's what puts `tenant` and
`clearance` into the **access token** (the JWT the resource services actually see and validate),
not just the ID token. Note `jsonType.label` differs: `"String"` for `tenant`, `"int"` for
`clearance` — `DocumentPermissionEvaluator` (Story 05) reads `clearance` via
`jwt.getClaim("clearance")` and checks `instanceof Number`, so this must actually decode as a JSON
number in the token, not a numeric string.

This client scope gets attached to clients in Task 4 — defining it doesn't do anything by itself
until a client's `defaultClientScopes` includes `"enterprise-claims"`.

### Task 4 — Four clients: one interactive, three service-facing

```json
  "clients": [
    {
      "clientId": "web-app",
      "name": "Interactive web / test client",
      "description": "Public client for the authorization_code flow; direct-access-grants left on ONLY to make curl-based demoing easy — disable it in a real deployment.",
      "enabled": true,
      "publicClient": true,
      "protocol": "openid-connect",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": false,
      "implicitFlowEnabled": false,
      "redirectUris": ["http://localhost:3000/*", "http://localhost:8080/*", "http://localhost:8085/*"],
      "webOrigins": ["+"],
      "defaultClientScopes": ["web-origins", "acr", "profile", "email", "roles", "enterprise-claims"]
    },
    {
      "clientId": "order-service",
      "name": "order-service (service-to-service caller)",
      "enabled": true,
      "publicClient": false,
      "protocol": "openid-connect",
      "secret": "order-service-secret",
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false,
      "serviceAccountsEnabled": true,
      "defaultClientScopes": ["roles", "enterprise-claims"]
    },
    {
      "clientId": "user-service",
      "name": "user-service (resource server)",
      "enabled": true,
      "publicClient": false,
      "bearerOnly": true,
      "protocol": "openid-connect",
      "secret": "user-service-secret"
    },
    {
      "clientId": "admin-service",
      "name": "admin-service (resource server)",
      "enabled": true,
      "publicClient": false,
      "bearerOnly": true,
      "protocol": "openid-connect",
      "secret": "admin-service-secret"
    }
  ],
```

Match each client to what it's *for* — getting the flags wrong here either breaks the demo or
quietly widens what a client can do:

- **`web-app`** — the only client a human ever authenticates through directly. `publicClient:
  true` (no secret; it's meant to run in a browser/curl, not hold a confidential secret).
  `directAccessGrantsEnabled: true` enables the Resource Owner Password Credentials (ROPC) grant
  purely so `curl -d grant_type=password` works for demoing (Story 07's example requests use
  exactly this) — the description field says explicitly this would be disabled in a real
  deployment, where only `standardFlowEnabled` (real browser login) would remain.
- **`order-service`** — `serviceAccountsEnabled: true`, `directAccessGrantsEnabled: false`. This is
  what makes `client_credentials` grant work for `OAuth2ClientConfig` (Story 04) — Keycloak
  auto-creates a service-account user for this client (Task 5 assigns it the `SERVICE` role).
  `secret: "order-service-secret"` must match `order-service`'s `application.yml`
  `client-secret` exactly.
- **`user-service` / `admin-service`** — `bearerOnly: true`. These clients never authenticate
  anyone or issue tokens themselves; `bearerOnly` marks them as pure resource servers whose only
  job is validating tokens issued for *other* clients. Their secrets exist for completeness but
  aren't used by any grant flow in this project.

Every client scope must be spelled exactly as configured — a resource service's
`security.resource.client-id` (Stories 03–05) is what `KeycloakRoleConverter` uses to read
`resource_access.<that-client-id>.roles`, but note none of these three clients actually define any
client-specific roles in this realm — the project relies on **realm roles** end-to-end, so
`resource_access` mapping exists in `KeycloakRoleConverter` for completeness/extensibility but is
never populated here. Don't add client roles unless you're deliberately extending the design.

### Task 5 — Five human users + one service account

```json
  "users": [
    {
      "username": "alice",
      "email": "alice@enterprise.example.com",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Alice",
      "lastName": "Admin",
      "credentials": [{ "type": "password", "value": "Passw0rd!", "temporary": false }],
      "attributes": { "tenant": ["tenant-a"], "clearance": ["3"] },
      "realmRoles": ["ADMIN", "USER"]
    },
    {
      "username": "bob",
      "email": "bob@enterprise.example.com",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Bob",
      "lastName": "User",
      "credentials": [{ "type": "password", "value": "Passw0rd!", "temporary": false }],
      "attributes": { "tenant": ["tenant-a"], "clearance": ["1"] },
      "realmRoles": ["USER"]
    },
    {
      "username": "carol",
      "email": "carol@enterprise.example.com",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Carol",
      "lastName": "Support",
      "credentials": [{ "type": "password", "value": "Passw0rd!", "temporary": false }],
      "attributes": { "tenant": ["tenant-a"], "clearance": ["2"] },
      "realmRoles": ["SUPPORT", "USER"]
    },
    {
      "username": "dave",
      "email": "dave@enterprise.example.com",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Dave",
      "lastName": "Editor",
      "credentials": [{ "type": "password", "value": "Passw0rd!", "temporary": false }],
      "attributes": { "tenant": ["tenant-b"], "clearance": ["2"] },
      "realmRoles": ["EDITOR", "USER"]
    },
    {
      "username": "root-admin",
      "email": "root-admin@enterprise.example.com",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Root",
      "lastName": "PlatformAdmin",
      "credentials": [{ "type": "password", "value": "Passw0rd!", "temporary": false }],
      "attributes": { "tenant": ["tenant-a"], "clearance": ["3"] },
      "realmRoles": ["PLATFORM_ADMIN", "ADMIN", "USER"]
    },
    {
      "username": "service-account-order-service",
      "enabled": true,
      "serviceAccountClientId": "order-service",
      "realmRoles": ["SERVICE"]
    }
  ]
}
```

These five users are deliberately chosen to exercise every branch of Story 05's ABAC evaluator and
Story 03's RBAC rules — keep this exact set (don't rename or drop any) since later stories'
manual verification steps refer to them by name:

| User | Roles | Tenant | Clearance | Exercises |
|---|---|---|---|---|
| `alice` | ADMIN, USER | tenant-a | 3 | ordinary admin RBAC |
| `bob` | USER | tenant-a | 1 | self-service only, low clearance |
| `carol` | SUPPORT, USER | tenant-a | 2 | read-mostly oversight role |
| `dave` | EDITOR, USER | tenant-b | 2 | write access, and the tenant-isolation test subject |
| `root-admin` | PLATFORM_ADMIN, ADMIN, USER | tenant-a | 3 | the one cross-tenant escape hatch |

The last entry, `service-account-order-service`, is **not a human login** — `serviceAccountClientId:
"order-service"` ties it to the `order-service` client's auto-provisioned service account (Task 4),
and `realmRoles: ["SERVICE"]` is what lets `getUserForServiceCall` (Story 03) and the route rule
`hasAnyRole('SERVICE','ADMIN')` accept its token.

All five human passwords are the intentionally-obvious demo value `Passw0rd!` — this is called out
as a known simplification (plaintext demo credentials), not an oversight; a real deployment would
never commit real user passwords to a realm-export file.

## Verification

You need a running Keycloak to actually test this — that's Story 07's docker-compose setup. Once
that's up, confirm the realm imported correctly and the custom claims actually appear:

```bash
# Get a token for alice via the ROPC grant (web-app client)
TOKEN=$(curl -s -X POST http://localhost:8080/realms/enterprise/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app \
  -d username=alice -d password='Passw0rd!' | jq -r .access_token)

# Decode the payload (no signature verification, just to inspect claims)
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

**Acceptance criteria:** the decoded payload contains `"realm_access": {"roles": [..., "ADMIN",
"USER", ...]}`, `"tenant": "tenant-a"`, and `"clearance": 3` (a JSON number, not `"3"` as a
string).

## What NOT to do yet

Don't try to start Keycloak with this file yet outside of Story 07's docker-compose setup — that's
where `--import-realm` and the volume mount that makes this file visible to the container both get
wired up. This story only produces the JSON file itself.
