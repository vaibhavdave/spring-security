# Build Plan: Enterprise Security Microservices, From Scratch

Twelve stories that take a junior engineer from an empty directory to this repository's full
system: a Gradle multi-module Spring Boot project demonstrating RBAC, ABAC, service-to-service
OAuth2, Keycloak identity, and Istio mesh security. Each story is self-contained, sequential, and
independently workable — pick up Story `NN` knowing only that stories `00..NN-1` are already done,
without needing any other context.

## How to use this plan

- **Work the stories in order.** Each one names its dependencies and what it unlocks; nothing
  later is buildable before its prerequisites are done.
- **Each story ends in a working, testable state.** Every "Definition of done" checklist is a real
  checkpoint — don't move to the next story until the current one's boxes are all checked.
- **Each story also says what *not* to build yet.** If you find yourself reaching for something
  from a later story, stop — that dependency will show up when its story arrives.
- **"Reliable details" means exactly that.** Config keys, class shapes, and SpEL expressions in
  these stories are specified precisely, especially anywhere a mistake would silently create a
  security hole (role mapping, ABAC checks, filter ordering). Where a task says "same pattern as
  Story X," it means it — don't improvise a different shape for consistency's sake.
- **When a story references the finished repository** (e.g. "check for a `docs/` tree"), that's
  the acceptance-criteria reference for that story, not a shortcut to skip the work.

## Story sequence

| # | Story | What it builds |
|---|---|---|
| 00 | [Prerequisites & Repository Bootstrap](00-prerequisites-and-bootstrap.md) | Tooling, empty git repo |
| 01 | [Gradle Multi-Module Scaffold](01-gradle-multi-module-scaffold.md) | Root build, wrapper, 4 module shells |
| 02 | [common-security Library](02-common-security-library.md) | JWT role mapping, RFC 7807 errors, tenant context, API-key auth, audit logging, security headers |
| 03 | [user-service — RBAC & Ownership](03-user-service-rbac.md) | First resource service: `@Secured`/`@PreAuthorize`/`@PostAuthorize`, `/internal` API |
| 04 | [order-service — OAuth2 Client](04-order-service-oauth2-client.md) | Client-credentials service-to-service call to `user-service` |
| 05 | [admin-service — ABAC](05-admin-service-abac.md) | Custom `PermissionEvaluator`, dual JWT/API-key auth |
| 06 | [Keycloak Realm Setup](06-keycloak-realm-setup.md) | Realm, roles, custom `tenant`/`clearance` claims, 5 users + 1 service account |
| 07 | [Docker Compose Local Dev](07-docker-compose-local-dev.md) | Dockerfiles, DB init, first real end-to-end run |
| 08 | [Kubernetes Base Manifests](08-kubernetes-base-manifests.md) | Namespace, Deployments, ServiceAccounts, Kustomize base |
| 09 | [Istio Service Mesh Security](09-istio-service-mesh-security.md) | mTLS, mesh JWT validation, per-service `AuthorizationPolicy` |
| 10 | [Test Suite & README](10-security-test-suite-and-readme.md) | Full-suite verification, project README |
| 11 | [Documentation & Diagrams](11-documentation-and-diagrams.md) | Diagram-first `docs/` tree |

## Dependency shape

Stories 00–02 are strictly linear. Stories 03, 04, and 05 build the three resource services —
04 depends on 03 (it calls `user-service`'s API), but 05 only depends on 02 and can be built
before or after 03/04 if you want to parallelize across engineers; the sequence above just picks
one reasonable order. From Story 06 onward everything is linear again: identity, then two
deployment shapes in increasing sophistication (Compose → Kubernetes → Kubernetes+Istio), then
verification and documentation.

## What "done" looks like

A fresh clone built by following Stories 00–11 in order should be behaviorally and structurally
equivalent to this repository: four Gradle modules, a Keycloak realm, Docker Compose and
Kubernetes+Istio deployment paths, a green `./gradlew test`, a README, and a `docs/` tree — with
every design decision along the way traceable to the story that made it, not left implicit.
