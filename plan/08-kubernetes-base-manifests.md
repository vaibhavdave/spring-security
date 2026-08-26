# Story 08 — Kubernetes Base Manifests

**Depends on:** [Story 07 — Docker Compose Local Dev Loop](07-docker-compose-local-dev.md) (reuses
the same Dockerfiles and the same realm/init-script content)
**Unlocks:** [Story 09 — Istio Service Mesh Security](09-istio-service-mesh-security.md)

## Goal

Translate the working Compose setup into plain Kubernetes manifests — namespace, Postgres,
Keycloak, and the three services, each with its own `Deployment` and, critically, its own
`ServiceAccount` — with no Istio-specific resources yet (that's the next story, layered on top via
a separate Kustomize overlay).

## Context

Everything under `k8s/base/` in this story is *cluster-agnostic* Kubernetes — it would work on any
cluster, Istio or not. The one forward-looking detail is the namespace label
`istio-injection: enabled`, which does nothing without Istio installed but is what makes sidecar
injection automatic once Story 09 installs it. Assembled with [`kind`](https://kind.sigs.k8s.io/)
as the reference local cluster (installed in Story 00); everything here also applies unmodified to
a real cluster (minikube, EKS, GKE, AKS), only the image-loading step in Task 6 differs.

**New to `Deployment`/`Service`/`ServiceAccount`/`Secret`/Kustomize? Read
[`CONCEPTS.md` §9–10](CONCEPTS.md#9-kubernetes-core-objects) first** — in particular, note *why*
each service gets its own `ServiceAccount` rather than a shared default one (Task 5's callout) —
that single decision is what Story 09's Istio identity rules will key off of later.

## Tasks

### Task 1 — Namespace

`k8s/base/namespace/namespace.yaml`:
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: enterprise-security
  labels:
    # Enables Istio automatic sidecar injection for every pod in this namespace.
    istio-injection: enabled
```

Every other manifest in this story sets `namespace: enterprise-security` explicitly — don't rely
on `kubectl apply -n enterprise-security` at the command line to supply it, since Story 09's
`kubectl apply -k k8s/istio` needs the namespace to be correct from the YAML itself.

### Task 2 — Shared Postgres credentials

One `Secret` holding every database password used in this deployment (four DB passwords, matching
Story 07's `db/postgres-init.sql` exactly), `k8s/base/postgres/secret.yaml`:
```yaml
# Demo-only plaintext Secret manifest. A real deployment would generate/rotate this via a
# secrets manager (Vault, External Secrets Operator, cloud KMS) rather than checking it into git.
apiVersion: v1
kind: Secret
metadata:
  name: postgres-credentials
  namespace: enterprise-security
type: Opaque
stringData:
  POSTGRES_SUPERUSER_PASSWORD: "postgres-superuser-demo-pw"
  USER_SERVICE_DB_PASSWORD: "user-service-demo-pw"
  ORDER_SERVICE_DB_PASSWORD: "order-service-demo-pw"
  ADMIN_SERVICE_DB_PASSWORD: "admin-service-demo-pw"
  KEYCLOAK_DB_PASSWORD: "keycloak-demo-pw"
```

### Task 3 — Postgres `Deployment` + `Service` (+ persistence)

`k8s/base/postgres/deployment.yaml` — three resources in one file (Kubernetes allows multiple
documents separated by `---`):

1. A `PersistentVolumeClaim` named `postgres-data`, `ReadWriteOnce`, `2Gi` — unlike Compose's named
   volume, Kubernetes needs this claim to exist before the pod can mount it.
2. A `Deployment` with one container, image `postgres:16-alpine`, env `POSTGRES_USER=postgres` and
   `POSTGRES_PASSWORD` sourced via `secretKeyRef` from `postgres-credentials` /
   `POSTGRES_SUPERUSER_PASSWORD` (never inline the password directly in the Deployment spec — that
   defeats the point of having a `Secret` at all). Mount two volumes: `postgres-data` at
   `/var/lib/postgresql/data`, and a `ConfigMap` named `init-scripts` at
   `/docker-entrypoint-initdb.d` (this ConfigMap gets generated automatically in Task 7 from the
   same `db/postgres-init.sql` Story 07 wrote — don't recreate that file, reference it). Add
   `readinessProbe`/`livenessProbe` both running `pg_isready -U postgres`.
3. A `Service` named `postgres` selecting `app: postgres`, exposing port `5432` — this Service name
   is what every other manifest's `DB_HOST` env var points at.

### Task 4 — Keycloak `Deployment` + `Service`

`k8s/base/keycloak/deployment.yaml`, same two-resource shape. Container image
`quay.io/keycloak/keycloak:25.0`, `args: ["start-dev", "--import-realm"]` (identical reasoning to
Story 07's compose command). Env vars: `KEYCLOAK_ADMIN=admin`, `KEYCLOAK_ADMIN_PASSWORD=admin`,
`KC_DB=postgres`, `KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak`, `KC_DB_USERNAME=keycloak`,
`KC_DB_PASSWORD` via `secretKeyRef` → `postgres-credentials` / `KEYCLOAK_DB_PASSWORD`,
`KC_HEALTH_ENABLED=true`. Mount a `ConfigMap` named `keycloak-realm-config` (generated in Task 7
from Story 06's `keycloak/realm-export.json`) at `/opt/keycloak/data/import`. Probes:
`readinessProbe`/`livenessProbe` as `httpGet` against `/health/ready` / `/health/live` on port
`8080` — Keycloak's built-in health endpoints (enabled by `KC_HEALTH_ENABLED`), not a shell probe
this time. `Service` named `keycloak`, port `8080`.

### Task 5 — Per-service `ServiceAccount` + `Deployment` + `Service`

Do this three times, once per resource service — the pattern is identical, only names/ports/env
differ. Using `user-service` as the template:

`k8s/base/user-service/serviceaccount.yaml`:
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: user-service
  namespace: enterprise-security
```

`k8s/base/user-service/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
  namespace: enterprise-security
  labels:
    app: user-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: user-service
  template:
    metadata:
      labels:
        app: user-service
    spec:
      # Distinct ServiceAccount per microservice is what Istio's AuthorizationPolicy keys off of
      # (spiffe://cluster.local/ns/enterprise-security/sa/user-service) to authorize which
      # workloads may call which routes at the mesh layer, independent of the JWT-level checks.
      serviceAccountName: user-service
      containers:
        - name: user-service
          image: enterprise-security/user-service:latest
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8081
          env:
            - name: DB_HOST
              value: postgres
            - name: DB_PORT
              value: "5432"
            - name: DB_NAME
              value: user_service
            - name: DB_USERNAME
              value: user_service
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: USER_SERVICE_DB_PASSWORD
            - name: KEYCLOAK_ISSUER_URI
              value: http://keycloak:8080/realms/enterprise
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 20
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 15
---
apiVersion: v1
kind: Service
metadata:
  name: user-service
  namespace: enterprise-security
spec:
  selector:
    app: user-service
  ports:
    - port: 8081
      targetPort: 8081
```

Notes that apply to all three deployments:
- `imagePullPolicy: IfNotPresent` — pull only if the tagged image isn't already present on the
  node; matters for `kind`, where you load images directly (Task 6) rather than pushing to a
  registry.
- Readiness/liveness probes hit `/actuator/health/readiness` and `/actuator/health/liveness` —
  these split endpoints exist because every service's `application.yml` (Stories 03–05) already
  sets `management.health.livenessstate.enabled` / `readinessstate.enabled: true`. Don't probe
  plain `/actuator/health` — the split endpoints are what distinguish "started but not ready to
  serve" from "process is alive."
- `KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/enterprise` — the in-cluster Kubernetes
  `Service` DNS name (`keycloak`, resolvable within the namespace), same reasoning as Compose's
  service-name networking in Story 07.

Repeat for `order-service` (port `8082`; env additionally needs `KEYCLOAK_TOKEN_URI`,
`ORDER_SERVICE_CLIENT_SECRET` — via `secretKeyRef` from a **new** `Secret`
`order-service-oauth2`/`ORDER_SERVICE_CLIENT_SECRET` you create in
`k8s/base/order-service/secret.yaml`, and `USER_SERVICE_BASE_URL: http://user-service:8081`) and
`admin-service` (port `8083`; env additionally needs `PARTNER_ACME_API_KEY` via `secretKeyRef` from
a new `Secret` `admin-service-apikeys`/`PARTNER_ACME_API_KEY` in
`k8s/base/admin-service/secret.yaml`). Both extra secrets follow the same demo-only-plaintext
pattern as `postgres-credentials`, and both need a one-line comment saying so, e.g.:

```yaml
# Demo-only. Must match the "order-service" client secret in keycloak/realm-export.json.
apiVersion: v1
kind: Secret
metadata:
  name: order-service-oauth2
  namespace: enterprise-security
type: Opaque
stringData:
  ORDER_SERVICE_CLIENT_SECRET: "order-service-secret"
```

```yaml
# Demo-only. Production would issue/rotate partner API keys through a proper secrets workflow,
# not a plaintext manifest.
apiVersion: v1
kind: Secret
metadata:
  name: admin-service-apikeys
  namespace: enterprise-security
type: Opaque
stringData:
  PARTNER_ACME_API_KEY: "demo-partner-acme-key"
```

Both values must match Story 06's realm export (`order-service`'s client secret) and Story 07's
Compose env (`PARTNER_ACME_API_KEY`) exactly — this is the same secret value expressed three ways
across three deployment mechanisms.

### Task 6 — Kustomize base: `k8s/base/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - namespace/namespace.yaml
  - postgres/secret.yaml
  - postgres/deployment.yaml
  - keycloak/deployment.yaml
  - user-service/serviceaccount.yaml
  - user-service/deployment.yaml
  - order-service/serviceaccount.yaml
  - order-service/secret.yaml
  - order-service/deployment.yaml
  - admin-service/serviceaccount.yaml
  - admin-service/secret.yaml
  - admin-service/deployment.yaml

# Generates ConfigMaps directly from the same canonical files docker-compose.yml mounts, so
# there's exactly one copy each of the realm definition and the DB init script.
configMapGenerator:
  - name: keycloak-realm-config
    namespace: enterprise-security
    files:
      - ../../keycloak/realm-export.json
  - name: postgres-init-scripts
    namespace: enterprise-security
    files:
      - init.sql=../../db/postgres-init.sql
```

`configMapGenerator` is the piece that ties this story back to Stories 06/07 instead of
duplicating their files: it generates the two ConfigMaps referenced in Tasks 3/4
(`postgres-init-scripts`, `keycloak-realm-config`) directly from `keycloak/realm-export.json` and
`db/postgres-init.sql` at `kubectl apply` / `kustomize build` time — there's exactly one source of
truth for the realm and the DB init script, reused by both Compose and Kubernetes.

### Task 7 — Validate and (optionally) apply against a local cluster

Sanity-check the rendered output before ever applying it:
```bash
kubectl kustomize k8s/base | less
```
Confirm: the namespace, both secrets, both ConfigMaps (with the full realm JSON / SQL script
embedded), and all three services' Deployments/ServiceAccounts/Services render without errors.

To actually run it (needs `kind`, from Story 00):
```bash
kind create cluster --name enterprise-security
kubectl cluster-info --context kind-enterprise-security

# Build + load each image (kind can't pull from your local Docker daemon directly)
docker build -f user-service/Dockerfile  -t enterprise-security/user-service:latest  .
docker build -f order-service/Dockerfile -t enterprise-security/order-service:latest .
docker build -f admin-service/Dockerfile -t enterprise-security/admin-service:latest .
kind load docker-image enterprise-security/user-service:latest  --name enterprise-security
kind load docker-image enterprise-security/order-service:latest --name enterprise-security
kind load docker-image enterprise-security/admin-service:latest --name enterprise-security

kubectl apply -k k8s/base
kubectl -n enterprise-security get pods -w
```

Every pod should reach `1/1` Ready (not `2/2` yet — that's Story 09, once Istio's sidecar is
injected). If you're targeting a real cluster instead of `kind`, skip the `kind create
cluster`/`kind load` steps and push the three images to a registry your cluster can pull from
instead, then adjust each `Deployment`'s `image:` field accordingly.

## Definition of done

- [ ] `kubectl kustomize k8s/base` renders without error
- [ ] (if applied) `kubectl -n enterprise-security get pods` shows all five workloads Running,
      `1/1` Ready
- [ ] Every `Secret` value matches its counterpart in Story 06 (`realm-export.json`) or Story 07
      (`docker-compose.yml`) exactly
- [ ] Each of the three services has its own `ServiceAccount`, not a shared/default one

## What NOT to do yet

No `Gateway`, `VirtualService`, `PeerAuthentication`, `RequestAuthentication`, or
`AuthorizationPolicy` resources here — those are Istio-specific and belong to
[Story 09](09-istio-service-mesh-security.md)'s **separate** overlay, layered on top of this base
rather than merged into it.
