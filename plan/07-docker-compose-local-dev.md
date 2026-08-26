# Story 07 — Docker & Docker Compose Local Dev Loop

**Depends on:** [Story 06 — Keycloak Realm Setup](06-keycloak-realm-setup.md) (all four services +
the realm must exist first)
**Unlocks:** [Story 08 — Kubernetes Base Manifests](08-kubernetes-base-manifests.md)

## Goal

Package each service as a container image and wire everything (Postgres, Keycloak, the three
services) into one `docker compose up` — the first point in the project where you can exercise a
**real, end-to-end** request: a real Keycloak token, validated for real, authorizing a real
cross-service call.

## Context

This is deliberately the *simplest* way to run the whole system — no Kubernetes, no Istio, no
service mesh. Every check that depends on those (mesh mTLS, `AuthorizationPolicy`) is absent here;
only the Spring Security layers built in Stories 02–05 are active. That's intentional: this story
exists for fast iteration on application-level security, and Story 08/09 layer the "real"
deployment shape on top later.

**New to Docker/multi-stage builds or Compose? Read
[`CONCEPTS.md` §8](CONCEPTS.md#8-containers--docker-basics) first** — in particular, the
service-name-not-`localhost` networking rule matters throughout this story's `docker-compose.yml`.

## Tasks

### Task 1 — Database seed script

Each service owns a separate Postgres database and role — Keycloak needs its own too. Create
`db/postgres-init.sql`:

```sql
-- Demo-only plaintext passwords, duplicated from k8s/base/postgres/secret.yaml and
-- docker-compose.yml since Postgres init scripts run as plain SQL with no secret indirection.
CREATE USER user_service WITH PASSWORD 'user-service-demo-pw';
CREATE DATABASE user_service OWNER user_service;

CREATE USER order_service WITH PASSWORD 'order-service-demo-pw';
CREATE DATABASE order_service OWNER order_service;

CREATE USER admin_service WITH PASSWORD 'admin-service-demo-pw';
CREATE DATABASE admin_service OWNER admin_service;

CREATE USER keycloak WITH PASSWORD 'keycloak-demo-pw';
CREATE DATABASE keycloak OWNER keycloak;
```

Four databases, four distinct roles, one per owner — no service, including Keycloak, ever connects
using another service's credentials or reaches into another service's database.

### Task 2 — One Dockerfile per Boot module

All three follow the same two-stage pattern: build once with a full JDK image (this stage isn't
shipped), then copy just the built jar into a minimal JRE-only runtime image running as a
non-root user. Create `user-service/Dockerfile`:

```dockerfile
# Build context is the repo root (multi-module Gradle build), not this directory.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :user-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=build /workspace/user-service/build/libs/user-service-0.1.0.jar app.jar
USER spring
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Create `order-service/Dockerfile` and `admin-service/Dockerfile` identically, substituting the
module name (`order-service` / `admin-service`), the jar path, and the `EXPOSE` port (`8082` /
`8083` respectively — match each service's `server.port` from its own story).

Three details that matter, not just style:
- **`COPY . .` with the repo root as build context.** A Gradle multi-module build can't build one
  module in isolation from a subdirectory context — `:user-service:bootJar` needs
  `settings.gradle.kts` and `:common-security`'s sources available too. You'll build these images
  from the repo root (`docker build -f user-service/Dockerfile .`, exactly as Story 07's compose
  file and Story 08's build steps do), not from inside each module's own directory.
- **`-x test`** — skips the test task during the image build. Tests already ran in CI/locally
  (Stories 03–05); re-running them on every image build would slow down the docker build for no
  benefit and risks failing the build on a flaky test unrelated to what's being deployed.
- **non-root user.** `addgroup -S spring && adduser -S spring -G spring` then `USER spring` before
  `ENTRYPOINT` — the container process never runs as root, standard container hardening
  independent of anything Spring Security does inside the JVM.

### Task 3 — `docker-compose.yml`

```yaml
# Fast local dev loop: no Istio, no Kubernetes — just Keycloak + Postgres + the three Spring
# services talking over the Docker network. The Kubernetes + Istio manifests under k8s/ are the
# production-shaped deployment; this is for iterating quickly on the application-level security
# (JWT roles, method security, ABAC, API keys) without needing a cluster.
name: enterprise-security

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres-superuser-demo-pw
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./db/postgres-init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "postgres"]
      interval: 5s
      timeout: 5s
      retries: 10

  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--import-realm"]
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: keycloak-demo-pw
      KC_HEALTH_ENABLED: "true"
    volumes:
      - ./keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/localhost/8080"]
      interval: 10s
      timeout: 5s
      retries: 15
      start_period: 30s

  user-service:
    build:
      context: .
      dockerfile: user-service/Dockerfile
    environment:
      DB_HOST: postgres
      DB_PORT: "5432"
      DB_NAME: user_service
      DB_USERNAME: user_service
      DB_PASSWORD: user-service-demo-pw
      KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/enterprise
    ports:
      - "8081:8081"
    depends_on:
      postgres:
        condition: service_healthy
      keycloak:
        condition: service_healthy

  order-service:
    build:
      context: .
      dockerfile: order-service/Dockerfile
    environment:
      DB_HOST: postgres
      DB_PORT: "5432"
      DB_NAME: order_service
      DB_USERNAME: order_service
      DB_PASSWORD: order-service-demo-pw
      KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/enterprise
      KEYCLOAK_TOKEN_URI: http://keycloak:8080/realms/enterprise/protocol/openid-connect/token
      ORDER_SERVICE_CLIENT_SECRET: order-service-secret
      USER_SERVICE_BASE_URL: http://user-service:8081
    ports:
      - "8082:8082"
    depends_on:
      postgres:
        condition: service_healthy
      keycloak:
        condition: service_healthy
      user-service:
        condition: service_started

  admin-service:
    build:
      context: .
      dockerfile: admin-service/Dockerfile
    environment:
      DB_HOST: postgres
      DB_PORT: "5432"
      DB_NAME: admin_service
      DB_USERNAME: admin_service
      DB_PASSWORD: admin-service-demo-pw
      KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/enterprise
      PARTNER_ACME_API_KEY: demo-partner-acme-key
    ports:
      - "8083:8083"
    depends_on:
      postgres:
        condition: service_healthy
      keycloak:
        condition: service_healthy

volumes:
  postgres-data:
```

Notice every `KEYCLOAK_ISSUER_URI` here points at `http://keycloak:8080` — the **Docker network
service name**, not `localhost`. Each `application.yml`'s `${KEYCLOAK_ISSUER_URI:...}` placeholder
(Stories 03–05) defaults to `localhost:8080` for running a service bare on your machine, but inside
Compose's network, `keycloak` is the only name that resolves to the right container. Same
reasoning for `USER_SERVICE_BASE_URL: http://user-service:8081` on `order-service` — that's how it
finds `user-service` without any service discovery infrastructure.

`--import-realm` on the Keycloak command is what actually loads Story 06's
`realm-export.json` — without it, the mounted file is just an inert file in the container.

### Task 4 — Bring it up and smoke-test it

```bash
docker compose up --build
```

Wait for all five containers (`postgres`, `keycloak`, `user-service`, `order-service`,
`admin-service`) to report healthy/running — Keycloak takes the longest (Postgres has to be ready
first, then it imports the realm on startup).

Run through this exact sequence — it's the smallest set of requests that touches every mechanism
built in Stories 02–06:

```bash
# 1. Get alice's token (OIDC login)
TOKEN=$(curl -s -X POST http://localhost:8080/realms/enterprise/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app \
  -d username=alice -d password='Passw0rd!' | jq -r .access_token)

# 2. Read her own profile (user-service, RBAC/ownership)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/users/me

# 3. Get bob's token and create an order (order-service -> user-service, client-credentials)
BOB_TOKEN=$(curl -s -X POST http://localhost:8080/realms/enterprise/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app \
  -d username=bob -d password='Passw0rd!' | jq -r .access_token)

curl -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{"item":"widget","quantity":2}' http://localhost:8082/api/orders

# 4. Get dave's token and attempt cross-tenant document access (admin-service, ABAC) — expect 403
DAVE_TOKEN=$(curl -s -X POST http://localhost:8080/realms/enterprise/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app \
  -d username=dave -d password='Passw0rd!' | jq -r .access_token)

curl -i -H "Authorization: Bearer $DAVE_TOKEN" http://localhost:8083/api/documents/<some-tenant-a-doc-id>

# 5. Partner API-key access (no JWT at all)
curl -H "X-API-Key: demo-partner-acme-key" http://localhost:8083/partner/documents/public
```

Request 2 succeeding proves Keycloak issued a valid token *and* `user-service` validated it via
JWKS. Request 3 succeeding proves `order-service` obtained its own client-credentials token *and*
`user-service`'s `/internal` route accepted it. Request 4 proves ABAC tenant isolation actually
fires against a real token (you'll need a real document id — create one first as an `EDITOR` or
`ADMIN` via `POST /api/documents`, matching Story 05's controller). Request 5 proves the API-key
path works independent of Keycloak entirely.

## Definition of done

- [ ] `docker compose up --build` brings up all five containers healthy
- [ ] All five smoke-test requests above return the expected status/body
- [ ] `docker compose down -v` cleans up (including the `postgres-data` volume) without error, so
      the next `up` starts from a clean re-import of the realm

## What NOT to do yet

No Kubernetes, no Istio yet — this compose setup has no service mesh, no mTLS, no
`AuthorizationPolicy`. Those are [Story 08](08-kubernetes-base-manifests.md) and
[Story 09](09-istio-service-mesh-security.md), which describe the *production-shaped* deployment
of the same four container images built here.
