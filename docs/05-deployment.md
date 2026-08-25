# Deployment

[← Docs index](README.md)

Two ways to run this project, matching the two directories that define them:
[`docker-compose.yml`](../docker-compose.yml) for fast local iteration, and
[`k8s/`](../k8s) for the production-shaped deployment. Full step-by-step commands are in the
[root README](../README.md#running-it) — this page is the map, not the manual.

## Option A — Docker Compose (no mesh)

```mermaid
flowchart LR
    dev(["you, on localhost"])
    subgraph net["Docker network: enterprise-security"]
        pg[("postgres:16-alpine\n:5432")]
        kc["keycloak:25.0\n:8080\n(--import-realm)"]
        us["user-service :8081"]
        os["order-service :8082"]
        as["admin-service :8083"]
    end
    dev -->|"localhost:8080-8083"| kc & us & os & as
    kc --> pg
    us --> pg
    os --> pg
    as --> pg
    os -->|"internal Docker DNS:\nhttp://user-service:8081"| us
```

One Postgres instance, three logical databases (`user_service`, `order_service`,
`admin_service`), seeded by [`db/postgres-init.sql`](../db/postgres-init.sql). No Istio, no mTLS,
no mesh-level authorization — only layers 5–8 from
[04 · Security Layers](04-security-layers.md) are active. Good for iterating on JWT roles, method
security, ABAC, and API keys without a cluster.

## Option B — Kubernetes + Istio (production-shaped)

```mermaid
flowchart TB
    ext(["external client"]) -->|"Host: api.enterprise-security.local"| igw

    subgraph cluster["kind cluster"]
        subgraph istiosys["namespace: istio-system"]
            igw["istio-ingressgateway"]
        end

        subgraph ns["namespace: enterprise-security (istio-injection=enabled)"]
            direction TB
            subgraph pgpod["pod: postgres"]
                pgc["container: postgres"]
                pgs["sidecar: istio-proxy"]
            end
            subgraph kcpod["pod: keycloak"]
                kcc["container: keycloak"]
                kcs["sidecar: istio-proxy"]
            end
            subgraph uspod["pod: user-service\n(sa: user-service)"]
                usc["container: user-service"]
                uss["sidecar: istio-proxy"]
            end
            subgraph ospod["pod: order-service\n(sa: order-service)"]
                osc["container: order-service"]
                oss["sidecar: istio-proxy"]
            end
            subgraph aspod["pod: admin-service\n(sa: admin-service)"]
                asc["container: admin-service"]
                ass["sidecar: istio-proxy"]
            end
        end
    end

    igw -->|"/api/users/**"| uspod
    igw -->|"/api/orders/**"| ospod
    igw -->|"/api/documents/**\n/partner/documents/**"| aspod
    igw -->|"/realms/**, /admin/**"| kcpod
    ospod -->|"/internal/users/**\n(only this identity allowed)"| uspod
    uspod --- pgpod
    ospod --- pgpod
    aspod --- pgpod
```

Every pod runs `2/2` containers: the app plus the Istio sidecar that terminates mTLS and enforces
`AuthorizationPolicy`. `k8s/istio/kustomization.yaml` layers the Istio resources
(`Gateway`, `VirtualService`, `PeerAuthentication`, `RequestAuthentication`,
`AuthorizationPolicy` × 4) on top of `k8s/base` (namespace, Postgres, Keycloak, the three
services — each with its own `Deployment` and `ServiceAccount`).

```mermaid
flowchart LR
    base["k8s/base\nnamespace, Postgres, Keycloak,\n3× Deployment + ServiceAccount"] --> overlay["k8s/istio\n(kustomize overlay)"]
    overlay --> gw["Gateway +\nVirtualService"]
    overlay --> pa["PeerAuthentication\n(STRICT mTLS)"]
    overlay --> ra["RequestAuthentication × 2\n(gateway + namespace)"]
    overlay --> ap["AuthorizationPolicy × 4\n(user/order/admin-service, postgres)"]
```

## Ports (Compose / port-forward from cluster)

| Service | Port |
|---|---|
| Keycloak | 8080 |
| user-service | 8081 |
| order-service | 8082 |
| admin-service | 8083 |
| Postgres | 5432 (Compose only) |

## Known simplifications

Carried over from the root README, called out rather than hidden: `ddl-auto: update` instead of
migrations, plaintext demo credentials in Secrets/ConfigMaps, the `web-app` client's
Resource-Owner-Password flow enabled purely for demo `curl` logins, an HTTP-only Istio `Gateway`
(no TLS termination), and manifests that were reviewed and validated as syntactically correct but
not applied against a live cluster in the environment this was built in.
