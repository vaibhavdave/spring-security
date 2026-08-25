# Architecture Overview

[← Docs index](README.md)

This project is four independently-deployable Spring Boot services that share one library
(`common-security`) and lean on three pieces of off-the-shelf infrastructure — Keycloak, Istio,
and Kubernetes — for everything that isn't application-level authorization. The diagrams below
show how those pieces fit together, from the outside in.

## System context

Who and what talks to the system, and through which front door.

```mermaid
flowchart TB
    client(["Browser / curl client"])
    partner(["Partner system\n(external, no OAuth2)"])

    subgraph mesh["Kubernetes cluster — namespace: enterprise-security (Istio-injected)"]
        direction TB
        gw["Istio Ingress Gateway\n(HTTP :80, JWT validated here too)"]
        us["user-service :8081"]
        os["order-service :8082"]
        as["admin-service :8083"]
        kc["Keycloak :8080\n(realm: enterprise)"]
        pg[("Postgres\n3 logical databases")]
    end

    client -->|"Bearer JWT"| gw
    partner -->|"X-API-Key"| gw
    gw -->|"/api/users/**"| us
    gw -->|"/api/orders/**"| os
    gw -->|"/api/documents/**, /partner/documents/**"| as
    gw -->|"/realms/**, /admin/**"| kc
    os -->|"client-credentials call\n/internal/users/{id}"| us
    us --- pg
    os --- pg
    as --- pg
    us -.->|"validate JWT (JWKS)"| kc
    os -.->|"validate JWT (JWKS)"| kc
    as -.->|"validate JWT (JWKS)"| kc
```

**What to notice:** the gateway has no route for `/internal/**` at all (see
[`k8s/istio/virtualservice.yaml`](../k8s/istio/virtualservice.yaml)) — that surface is reachable
only from inside the mesh, by `order-service`. Every service validates its own JWT against
Keycloak's JWKS endpoint independently; nothing trusts the gateway's validation alone (see
[04 · Security Layers](04-security-layers.md)).

## Container / module view

Inside the boundary, `common-security` is a shared auto-configuration library, not a service —
every box below except Keycloak and Postgres is a Spring Boot application built from this repo.

```mermaid
flowchart LR
    subgraph shared["common-security (shared library, no HTTP port)"]
        direction TB
        jwt["KeycloakRoleConverter\nrealm_access/resource_access → GrantedAuthority"]
        apikey["ApiKeyAuthFilter\nX-API-Key → ApiKeyAuthenticationToken"]
        tenant["TenantContextFilter\nJWT 'tenant' claim → TenantContext"]
        err["RestAuthenticationEntryPoint /\nRestAccessDeniedHandler /\nGlobalExceptionHandler\n→ RFC 7807 ProblemDetail"]
        audit["AuditLogAspect\n@Audited → structured log line"]
        headers["SecurityHeadersCustomizer\nCSP, HSTS, frame-deny, ..."]
    end

    us["user-service\nRBAC + ownership\n@PreAuthorize/@PostAuthorize/@Secured"]
    os["order-service\nOAuth2 client-credentials\n(calls user-service)"]
    as["admin-service\nABAC PermissionEvaluator\n+ API-key partner auth"]

    shared -.->|"Spring Boot auto-configuration"| us
    shared -.->|"Spring Boot auto-configuration"| os
    shared -.->|"Spring Boot auto-configuration"| as
    os -->|"WebClient + client-credentials token"| us
```

| Module | Role | Depends on |
|---|---|---|
| `common-security` | Shared auto-configuration: JWT role mapping, tenant propagation, API-key auth, RFC 7807 errors, audit logging aspect, security headers. | — |
| `user-service` | Owns `User`. RBAC + ownership checks; exposes `/internal/**` for service-to-service reads. | `common-security` |
| `order-service` | Owns `Order`. OAuth2 **client** (client-credentials) to call `user-service`; OAuth2 **resource server** for inbound calls. | `common-security` |
| `admin-service` | Owns `Document`. Custom `PermissionEvaluator` (RBAC + ABAC), multi-tenancy, API-key partner auth. | `common-security` |

Each service has its own database, port, and Kubernetes `Deployment`/`ServiceAccount` — there is
no shared database and no synchronous call other than `order-service → user-service`.

## Where to go next

- [02 · Request Flows](02-request-flows.md) — sequence diagrams for the flows implied by the
  arrows above
- [04 · Security Layers](04-security-layers.md) — why the same check (e.g. "is this
  `order-service`?") shows up at more than one layer
- [05 · Deployment](05-deployment.md) — how these boxes map onto pods, sidecars, and Kustomize overlays
