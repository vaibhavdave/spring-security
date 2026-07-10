# Enterprise Security Microservices

A Gradle multi-module Spring Boot project demonstrating the authentication and authorization
patterns most commonly used in enterprise, microservices-based systems — built around **Keycloak**
(identity), **Istio** (mesh security), and **Kubernetes** (deployment), with Spring Security
handling the fine-grained, application-level decisions.

## Why this shape

Identity, service discovery, and edge routing are deliberately *not* reimplemented in Spring —
Keycloak, Kubernetes, and Istio already do those jobs in a real deployment, so the project leans on
them instead of a self-hosted Spring Authorization Server / Eureka / Spring Cloud Gateway. What's
left for Spring Security to own is exactly what it's good at: validating tokens, and deciding
"can *this* caller do *this* thing to *this* resource."

## Modules

| Module | Role |
|---|---|
| `common-security` | Shared library: Keycloak→GrantedAuthority mapping, tenant context propagation, API key auth, RFC 7807 error contracts, audit logging aspect. Exposed as a Spring Boot auto-configuration. |
| `user-service` | RBAC, ownership checks, `@PreAuthorize`/`@PostAuthorize`/`@Secured` |
| `order-service` | OAuth2 client-credentials service-to-service calls (calls `user-service`) |
| `admin-service` | Custom `PermissionEvaluator` (RBAC + ABAC), multi-tenancy, API-key partner auth |

Each service is an independent Spring Boot application with its own database, port, and
Kubernetes Deployment/ServiceAccount.

## Security practices demonstrated

**Authentication**
- OIDC/OAuth2 via Keycloak (JWT access tokens, `realm_access` + `resource_access` roles)
- Client-credentials grant for service-to-service calls (`order-service` → `user-service`)
- API key authentication for a simulated external-partner integration (`admin-service`)
- Custom claims (`tenant`, `clearance`) issued by Keycloak and consumed for ABAC

**Authorization**
- Role-based access control via JWT authorities (`@PreAuthorize`, `@PostAuthorize`, `@Secured` — all three are used, deliberately, to show the different shapes of the same underlying mechanism)
- Ownership checks (`#id == authentication.name`, i.e. the JWT `sub` claim)
- Attribute-based access control: a custom `PermissionEvaluator`
  (`DocumentPermissionEvaluator`) enforcing tenant isolation and a clearance-vs-classification
  check *ahead of* role checks, with one deliberate cross-tenant escape hatch (`PLATFORM_ADMIN`)
- Layered service-to-service authorization: an app-level `hasRole('SERVICE')` check *and* an
  independent mesh-level Istio `AuthorizationPolicy` keyed off SPIFFE workload identity, so a
  single compromised layer isn't enough

**Mesh / edge (Istio)**
- Namespace-wide **STRICT mTLS** (`PeerAuthentication`)
- JWT validation at both the ingress gateway and each service (`RequestAuthentication`)
- Per-service `AuthorizationPolicy` restricting callers by SPIFFE identity — notably,
  `/internal/**` on `user-service` is reachable *only* from `order-service`'s ServiceAccount
- The ingress `VirtualService` has no route for `/internal/**` at all — it's unreachable from
  outside the cluster by construction, not just by policy

**Cross-cutting**
- Consistent RFC 7807 (`ProblemDetail`) error responses for 401/403 across all services
- Structured audit logging (`@Audited`) for state-changing operations
- Hardened response headers (CSP, HSTS, frame-deny, permissions-policy)
- Demo-only secrets are clearly marked as such everywhere they appear

## Known simplifications (called out, not hidden)

- `ddl-auto: update` instead of Flyway/Liquibase migrations
- Plaintext demo credentials in Secrets/ConfigMaps/`application.yml` — a real deployment would use
  a secrets manager (Vault, External Secrets Operator, cloud KMS)
- `web-app`'s Keycloak client has the Resource Owner Password (`direct-access-grants`) flow
  enabled purely so tokens can be minted with `curl` for demoing; disable it in a real deployment
- HTTP-only Istio Gateway (no TLS termination)
- The Kubernetes/Istio manifests were validated as syntactically-correct YAML and reviewed
  manually, but not applied against a live cluster (no `kubectl`/`istioctl` in the environment
  this was built in) — validate with `kubectl kustomize` / `istioctl analyze` before applying.

## Running it

### Option A — Docker Compose (fastest, no mesh)

Good for iterating on the application-level security (JWT roles, method security, ABAC, API
keys) without a cluster.

```bash
docker compose up --build
```

This starts Postgres, Keycloak (auto-imports the realm), and the three services:

| Service | Port |
|---|---|
| Keycloak | 8080 |
| user-service | 8081 |
| order-service | 8082 |
| admin-service | 8083 |

### Option B — Kubernetes + Istio (the "real" deployment shape)

Requires a cluster with Istio installed and `istioctl`/`kubectl` available, plus the three service
images built and loadable into the cluster (e.g. `kind load docker-image` after building each
service's `Dockerfile`, or push to a registry the cluster can pull from).

```bash
# Build images (repeat per service)
docker build -f user-service/Dockerfile -t enterprise-security/user-service:latest .
docker build -f order-service/Dockerfile -t enterprise-security/order-service:latest .
docker build -f admin-service/Dockerfile -t enterprise-security/admin-service:latest .

# Apply base resources + Istio security config
kubectl apply -k k8s/istio
```

`k8s/istio/kustomization.yaml` layers Istio's `Gateway`/`VirtualService`/`PeerAuthentication`/
`RequestAuthentication`/`AuthorizationPolicy` on top of `k8s/base` (namespace, Postgres, Keycloak,
the three services). Point `curl` at the Istio ingress gateway with `Host: api.enterprise-security.local`
(or add that host to `/etc/hosts` against the gateway's external IP).

## Test users (seeded by `keycloak/realm-export.json`)

| Username | Password | Realm roles | tenant | clearance |
|---|---|---|---|---|
| alice | Passw0rd! | ADMIN, USER | tenant-a | 3 |
| bob | Passw0rd! | USER | tenant-a | 1 |
| carol | Passw0rd! | SUPPORT, USER | tenant-a | 2 |
| dave | Passw0rd! | EDITOR, USER | tenant-b | 2 |
| root-admin | Passw0rd! | PLATFORM_ADMIN, ADMIN, USER | tenant-a | 3 |

`order-service`'s Keycloak service account (`service-account-order-service`) carries the `SERVICE`
realm role used for its client-credentials calls to `user-service`.

## Example requests

Get a user token (demo-only ROPC flow via the `web-app` client):

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/realms/enterprise/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app \
  -d username=alice -d password='Passw0rd!' | jq -r .access_token)
```

Read your own profile:

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/users/me
```

Create an order (bob, USER role — validated against user-service via order-service's own
client-credentials call):

```bash
BOB_TOKEN=$(curl -s -X POST http://localhost:8080/realms/enterprise/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app \
  -d username=bob -d password='Passw0rd!' | jq -r .access_token)

curl -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{"item":"widget","quantity":2}' http://localhost:8082/api/orders
```

Attempt cross-tenant document access (dave, tenant-b, hits a tenant-a document — expect 403):

```bash
DAVE_TOKEN=$(curl -s -X POST http://localhost:8080/realms/enterprise/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app \
  -d username=dave -d password='Passw0rd!' | jq -r .access_token)

curl -i -H "Authorization: Bearer $DAVE_TOKEN" http://localhost:8083/api/documents/<some-tenant-a-doc-id>
```

Partner API-key access (no JWT at all):

```bash
curl -H "X-API-Key: demo-partner-acme-key" http://localhost:8083/partner/documents/public
```

## Testing

```bash
./gradlew test
```

Each service has MockMvc tests that exercise real HTTP requests through the full security chain
(route rules → method security → `PermissionEvaluator`/ownership logic), using
`SecurityMockMvcRequestPostProcessors.jwt()` to construct tokens with specific roles/claims rather
than mocking Spring Security itself. `common-security` and `admin-service` additionally have pure
unit tests for the JWT role mapping and the ABAC `PermissionEvaluator` in isolation.
