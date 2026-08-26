# Story 09 — Istio Service Mesh Security

**Depends on:** [Story 08 — Kubernetes Base Manifests](08-kubernetes-base-manifests.md)
**Unlocks:** [Story 10 — Security Test Suite & README](10-security-test-suite-and-readme.md)

## Goal

Layer mesh-level security on top of the working base deployment: mutual TLS between every pod,
JWT validation *again* (independently of what each Spring service already does), and per-service
`AuthorizationPolicy` rules keyed on cryptographic workload identity rather than anything a caller
presents in a request — all as a **separate Kustomize overlay**, not a modification of Story 08's
base manifests.

## Context

Everything in Story 08 works without Istio installed at all. This story is what turns the
deployment from "Kubernetes running some containers" into the mesh-secured shape the project is
actually about: identity-based transport security, redundant JWT validation at the mesh edge, and
authorization rules that don't trust any single layer alone. If you've read
`docs/04-security-layers.md` in the finished repo, this story builds layers 1–4 of that stack —
Stories 02–05 already built layers 5–8.

**New to service meshes, sidecars, mTLS, or SPIFFE identity? Read
[`CONCEPTS.md` §11–12](CONCEPTS.md#11-service-mesh--istio) before starting** — this story only
makes sense once you can distinguish "the mesh checked this" from "Spring Security checked this,"
since almost every resource here deliberately duplicates a check Stories 02–05 already built.

## Tasks

### Task 1 — Install Istio on the cluster

```bash
istioctl install --set profile=demo -y
kubectl get pods -n istio-system   # wait for istiod + istio-ingressgateway to be Running
```

The `demo` profile enables the ingress gateway and reasonable defaults for local use — a
production install would pick a minimal profile and layer on exactly the components needed, but
`demo` is the right choice for reproducing this project's local-cluster setup.

Every namespace's pods only get a sidecar injected if the namespace carries the
`istio-injection: enabled` label — that's why Story 08's `namespace.yaml` set it up front, even
though it did nothing without Istio installed.

### Task 2 — Namespace-wide strict mTLS

`k8s/istio/peer-authentication.yaml`:
```yaml
# Every pod-to-pod connection in this namespace must use mutual TLS — Istio issues each pod a
# SPIFFE identity certificate (spiffe://cluster.local/ns/enterprise-security/sa/<serviceaccount>)
# and refuses plaintext. This is the mesh-level analogue of the app-level JWT checks: it proves
# *which workload* is calling, independent of whatever bearer token that workload presents.
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: enterprise-security
spec:
  mtls:
    mode: STRICT
```

One resource, `mode: STRICT`, applies to the whole namespace (no `selector`). This is why Story
08's `ServiceAccount` per service (not a shared default) matters — the SPIFFE identity Istio
issues each pod is derived directly from `namespace + service account name`, and that identity is
what every `AuthorizationPolicy` below keys off of.

### Task 3 — Ingress gateway routing

`k8s/istio/gateway.yaml`:
```yaml
# HTTP-only ingress for local/demo use. A real deployment would terminate TLS here (a Secret
# holding the cert, credentialName + HTTPS server block) rather than serving plaintext on :80.
apiVersion: networking.istio.io/v1
kind: Gateway
metadata:
  name: enterprise-gateway
  namespace: enterprise-security
spec:
  selector:
    istio: ingressgateway
  servers:
    - port:
        number: 80
        name: http
        protocol: HTTP
      hosts:
        - "api.enterprise-security.local"
```

`k8s/istio/virtualservice.yaml`:
```yaml
# Deliberately has no route for /internal/** — that surface only exists for in-mesh
# service-to-service calls (see authorization-policy-user-service.yaml) and is simply
# unreachable from outside the cluster through this Gateway.
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: enterprise-routes
  namespace: enterprise-security
spec:
  hosts:
    - "api.enterprise-security.local"
  gateways:
    - enterprise-gateway
  http:
    - match:
        - uri:
            prefix: /realms/
        - uri:
            prefix: /resources/
        - uri:
            prefix: /admin/
      route:
        - destination:
            host: keycloak.enterprise-security.svc.cluster.local
            port:
              number: 8080
    - match:
        - uri:
            prefix: /api/users/
      route:
        - destination:
            host: user-service.enterprise-security.svc.cluster.local
            port:
              number: 8081
    - match:
        - uri:
            prefix: /api/orders/
      route:
        - destination:
            host: order-service.enterprise-security.svc.cluster.local
            port:
              number: 8082
    - match:
        - uri:
            prefix: /api/documents/
        - uri:
            prefix: /partner/documents/
      route:
        - destination:
            host: admin-service.enterprise-security.svc.cluster.local
            port:
              number: 8083
```

Read the comment at the top again before moving on — the *absence* of an `/internal/` route here
is not an oversight, it's the mechanism that makes `user-service`'s internal API unreachable from
outside the cluster **by construction**, a stronger guarantee than any `AuthorizationPolicy` rule
(which only blocks a request that *arrives*; this VirtualService means the request has nowhere to
even be routed to).

### Task 4 — JWT validation at the mesh layer

`k8s/istio/request-authentication.yaml` — two resources in one file:
```yaml
# Two layers of the same JWT check, deliberately overlapping with Spring Security's own
# validation in each service: the ingress gateway validates tokens for anything entering the
# mesh, and each service validates again for anything reaching it — a compromised or
# misconfigured route inside the mesh still can't smuggle an unvalidated/forged token through.
apiVersion: security.istio.io/v1
kind: RequestAuthentication
metadata:
  name: keycloak-jwt-ingress
  namespace: istio-system
spec:
  selector:
    matchLabels:
      istio: ingressgateway
  jwtRules:
    - issuer: "http://keycloak.enterprise-security.svc.cluster.local:8080/realms/enterprise"
      jwksUri: "http://keycloak.enterprise-security.svc.cluster.local:8080/realms/enterprise/protocol/openid-connect/certs"
      forwardOriginalToken: true
---
apiVersion: security.istio.io/v1
kind: RequestAuthentication
metadata:
  name: keycloak-jwt-services
  namespace: enterprise-security
spec:
  # No selector: applies to every workload in the namespace.
  jwtRules:
    - issuer: "http://keycloak.enterprise-security.svc.cluster.local:8080/realms/enterprise"
      jwksUri: "http://keycloak.enterprise-security.svc.cluster.local:8080/realms/enterprise/protocol/openid-connect/certs"
      forwardOriginalToken: true
```

Two `RequestAuthentication` resources, in **two different namespaces**: one in `istio-system`
selecting only the ingress gateway pods (`istio: ingressgateway`), one in `enterprise-security`
with no selector at all (applies to every workload there). `forwardOriginalToken: true` on both —
without it, Istio would strip the JWT after validating it, and the Spring services downstream
would have nothing left to parse. A `RequestAuthentication` on its own only *validates* a
presented token (or allows anonymous traffic through) — Task 5's `AuthorizationPolicy` resources
are what actually require a valid token's claims on specific routes via `request.auth.claims[...]`.

### Task 5 — Per-service `AuthorizationPolicy`: identity-based, not token-based

Four files, one per workload that needs mesh-level protection (three services + Postgres) — plus
one for Keycloak. Each expresses the same idea: *who (by SPIFFE identity) may reach which paths.*

`k8s/istio/authorization-policy-user-service.yaml`:
```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: user-service-authz
  namespace: enterprise-security
spec:
  selector:
    matchLabels:
      app: user-service
  action: ALLOW
  rules:
    # End-user traffic: anything that made it through the ingress gateway with a JWT Istio
    # already validated (RequestAuthentication populates request.auth.claims only for valid tokens).
    - from:
        - source:
            principals: ["cluster.local/ns/istio-system/sa/istio-ingressgateway-service-account"]
      to:
        - operation:
            paths: ["/api/users/*", "/actuator/health*"]
      when:
        - key: request.auth.claims[iss]
          values: ["http://keycloak.enterprise-security.svc.cluster.local:8080/realms/enterprise"]
    # Service-to-service traffic: only order-service's workload identity may reach /internal/**,
    # enforced here via mTLS-backed SPIFFE identity — a separate, orthogonal control from the
    # hasRole('SERVICE') check Spring Security applies to the same claim inside the JWT.
    - from:
        - source:
            principals: ["cluster.local/ns/enterprise-security/sa/order-service"]
      to:
        - operation:
            paths: ["/internal/*"]
```

This is the policy to understand most carefully — it's the mesh-layer counterpart of Story 03's
`/internal/**` route rule and `getUserForServiceCall`'s `@PreAuthorize`, and the three checks are
deliberately independent: this rule authorizes *based on which workload is calling* (SPIFFE
identity, cryptographically proven by mTLS), completely separate from *what role the JWT that
workload presents claims to have*.

`k8s/istio/authorization-policy-order-service.yaml` — same first-rule shape, gateway principal,
paths `["/api/orders/*", "/actuator/health*"]`, same `iss` claim check — but **no** second rule,
since `order-service` exposes no internal-only API of its own.

`k8s/istio/authorization-policy-admin-service.yaml`:
```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: admin-service-authz
  namespace: enterprise-security
spec:
  selector:
    matchLabels:
      app: admin-service
  action: ALLOW
  rules:
    # Staff traffic: Keycloak JWT required.
    - from:
        - source:
            principals: ["cluster.local/ns/istio-system/sa/istio-ingressgateway-service-account"]
      to:
        - operation:
            paths: ["/api/documents/*", "/actuator/health*"]
      when:
        - key: request.auth.claims[iss]
          values: ["http://keycloak.enterprise-security.svc.cluster.local:8080/realms/enterprise"]
    # Partner traffic: authenticates with X-API-Key at the application layer instead of a
    # Keycloak JWT, so this rule only constrains *where the request may come from*
    # (through the ingress gateway) — the credential itself is checked by ApiKeyAuthFilter.
    - from:
        - source:
            principals: ["cluster.local/ns/istio-system/sa/istio-ingressgateway-service-account"]
      to:
        - operation:
            paths: ["/partner/documents/*"]
```

Notice the second rule has **no** `when` clause requiring a valid `iss` claim — it can't, because
partner traffic never carries a Keycloak JWT at all (Story 05's API-key auth). Mesh-level policy
can only constrain *where the request comes from*; the actual credential check for this path
happens entirely inside `admin-service`'s `ApiKeyAuthFilter`.

`k8s/istio/authorization-policy-postgres.yaml`:
```yaml
# Illustrates that mesh identity governs the data layer too, not just HTTP APIs: only the
# workloads that actually own a database on this instance may open a connection to it, checked
# against the mTLS-issued SPIFFE identity regardless of what DB credentials the connection itself
# presents.
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: postgres-authz
  namespace: enterprise-security
spec:
  selector:
    matchLabels:
      app: postgres
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - cluster.local/ns/enterprise-security/sa/user-service
              - cluster.local/ns/enterprise-security/sa/order-service
              - cluster.local/ns/enterprise-security/sa/admin-service
              - cluster.local/ns/enterprise-security/sa/default
```

`sa/default` is in this list because Keycloak's `Deployment` (Story 08, Task 4) never set a
`serviceAccountName`, so it runs under the namespace's `default` service account — that's the
identity its own Postgres connection presents.

`k8s/istio/authorization-policy-keycloak.yaml`:
```yaml
# Keycloak needs to be reachable both from outside (browser login, ingress-routed token/userinfo
# calls) and from every service in-mesh (OIDC discovery + JWKS fetch, client-credentials token
# requests). Unlike the app services, it isn't scoped to a handful of paths since it is itself an
# infrastructure dependency, but it's still restricted to known callers rather than left open to
# the whole cluster.
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: keycloak-authz
  namespace: enterprise-security
spec:
  selector:
    matchLabels:
      app: keycloak
  action: ALLOW
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/istio-system/sa/istio-ingressgateway-service-account"]
    - from:
        - source:
            namespaces: ["enterprise-security"]
```

### Task 6 — The overlay itself: `k8s/istio/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../base
  - peer-authentication.yaml
  - gateway.yaml
  - virtualservice.yaml
  - request-authentication.yaml
  - authorization-policy-keycloak.yaml
  - authorization-policy-user-service.yaml
  - authorization-policy-order-service.yaml
  - authorization-policy-admin-service.yaml
  - authorization-policy-postgres.yaml
```

`- ../base` is the whole reason this is an *overlay*, not a rewrite: everything from Story 08
still applies unmodified, this file just adds Istio's resources on top in one `kubectl apply`.

### Task 7 — Apply and verify mesh security actually took effect

```bash
kubectl kustomize k8s/istio | less        # sanity-check the rendered output first
kubectl apply -k k8s/istio

kubectl -n enterprise-security get pods -w   # wait for every pod to show 2/2 (app + istio-proxy)

kubectl -n enterprise-security rollout status deployment/keycloak
kubectl -n enterprise-security rollout status deployment/user-service
kubectl -n enterprise-security rollout status deployment/order-service
kubectl -n enterprise-security rollout status deployment/admin-service

# STRICT mTLS should show STRICT for every workload in the namespace
istioctl x describe pod -n enterprise-security \
  $(kubectl -n enterprise-security get pod -l app=user-service -o jsonpath='{.items[0].metadata.name}')

# No config errors/warnings across the whole mesh config
istioctl analyze -n enterprise-security
```

Then reach the services the way an external caller would — through the ingress gateway, not by
port-forwarding straight to a pod:
```bash
kubectl -n istio-system port-forward svc/istio-ingressgateway 8080:80
```
In another terminal (add `api.enterprise-security.local` to `/etc/hosts` pointing at `127.0.0.1`
to avoid passing `-H Host` on every request):
```bash
curl -H "Host: api.enterprise-security.local" \
  http://localhost:8080/realms/enterprise/.well-known/openid-configuration
```
Then repeat Story 07's five-request smoke test through `http://api.enterprise-security.local:8080`
instead of `localhost:<port>` — with one deliberate difference: there's no way to reach
`/internal/**` this way at all (confirm that a request to it through the gateway fails to route,
not just gets a 403 — that's the `VirtualService`'s absence of a route doing its job, not an
`AuthorizationPolicy` denial).

Tear down when done:
```bash
kubectl delete -k k8s/istio
kind delete cluster --name enterprise-security
```

## Definition of done

- [ ] `kubectl kustomize k8s/istio` renders without error and includes every resource from
      `../base` plus all seven Istio resources
- [ ] `istioctl analyze -n enterprise-security` reports no errors or warnings
- [ ] Every pod in the namespace shows `2/2` Ready (app container + `istio-proxy` sidecar)
- [ ] `istioctl x describe pod` for any workload reports `STRICT` mTLS
- [ ] All five smoke-test requests from Story 07 succeed through the ingress gateway, **except**
      an `/internal/**` request, which has no route to succeed *or* fail against
- [ ] `/etc/hosts` / `-H Host` note: every `curl` in this story's verification targets
      `api.enterprise-security.local`, not a bare `localhost:<port>` — that's what actually
      exercises the `Gateway`/`VirtualService` routing this story built

## What NOT to do yet

This story does not add TLS termination at the gateway (the `Gateway` resource is deliberately
HTTP-only, as its own comment says) — that, and swapping the demo Istio profile for a minimal
production one, are real-deployment concerns explicitly out of scope for this project, not a gap
to fill in a later story.
