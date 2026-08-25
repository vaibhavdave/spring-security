# Story 10 — Full Test Suite Verification & Project README

**Depends on:** [Story 09 — Istio Service Mesh Security](09-istio-service-mesh-security.md) (needs
every prior story finished to write an accurate README)
**Unlocks:** [Story 11 — Documentation & Diagrams](11-documentation-and-diagrams.md)

## Goal

Confirm the whole test suite is green end to end across all four modules, then write the root
`README.md` that ties every earlier story together into the single entry point a new reader
(including the junior engineer who just finished Stories 00–09) starts from.

## Context

By this point every module has its own tests (Story 02's `KeycloakRoleConverterTest`, Story 03's
`UserControllerSecurityTest`, Story 04's `OrderControllerSecurityTest`, Story 05's three
admin-service test files) and the system has been exercised manually twice (Story 07's Compose
smoke test, Story 09's mesh-routed repeat of it). This story is the wrap-up: one command that
proves all of it still works together, and the documentation that makes the finished repo usable
by someone who wasn't the one who built it.

## Tasks

### Task 1 — Run the full suite from the repo root

```bash
./gradlew test
```

This runs every module's test suite in one pass — `common-security`'s
`KeycloakRoleConverterTest`; `user-service`'s `UserServiceApplicationTests` and
`UserControllerSecurityTest`; `order-service`'s `OrderServiceApplicationTests` and
`OrderControllerSecurityTest`; `admin-service`'s `AdminServiceApplicationTests`,
`DocumentPermissionEvaluatorTest`, `DocumentControllerSecurityTest`, and
`PartnerDocumentControllerSecurityTest`. All of it runs against H2 (per each module's
`application-test.yml` from Stories 02–05) — no Docker, no Keycloak, no cluster required for this
command to pass.

**If anything fails here**, don't patch it from this story — go back to the story that owns the
failing class (the test's package tells you which: `common.jwt` → Story 02, `userservice` →
Story 03, `orderservice` → Story 04, `adminservice` → Story 05) and fix the underlying
implementation. This story only verifies; it doesn't introduce new application logic.

### Task 2 — Confirm test-count expectations

As a sanity check that nothing was silently skipped, the finished suite should report **at least**:

| Module | Test classes | Notable coverage |
|---|---|---|
| `common-security` | 1 | JWT role mapping (realm roles, client-scoped resource roles, missing claims) |
| `user-service` | 2 | App context boots; 12 RBAC/ownership/internal-route scenarios |
| `order-service` | 2 | App context boots; 7 client-credentials/ownership/RBAC scenarios |
| `admin-service` | 4 | App context boots; 7 ABAC unit cases; 6 full-chain HTTP scenarios; 3 API-key scenarios |

If your counts are notably lower, some scenario from Stories 02–05 didn't make it into the test
file — cross-check against each story's own test task before moving on.

### Task 3 — Write `README.md`

This is the front door to the whole repository — everything in it should be traceable to a
specific earlier story, not new information invented at this stage. Structure it as:

**Title + one paragraph** framing what the project demonstrates and the three infrastructure
pieces it leans on (Keycloak, Istio, Kubernetes) versus what Spring Security itself is left to own
(validating tokens, deciding "can *this* caller do *this* thing to *this* resource"). This is the
one-sentence answer to "why does this project exist," synthesized from what you built across every
story — write it in your own words, but it should land on the same idea: identity, discovery, and
edge routing are delegated to purpose-built infrastructure rather than reimplemented in Spring.

**"Why this shape"** — a short section explaining the architectural choice explicitly: no
self-hosted Spring Authorization Server, no Eureka, no Spring Cloud Gateway, because Keycloak/K8s/
Istio already solve those problems in a real deployment.

**"Modules"** — the same four-row table from Story 01's context section (module → role →
depends-on), plus the one-line note that each service has its own database, port, and Kubernetes
`Deployment`/`ServiceAccount`.

**"Security practices demonstrated"** — four subsections, each a bullet list synthesizing what you
actually built:
- *Authentication*: OIDC/OAuth2 via Keycloak, client-credentials for service-to-service, API-key
  for the partner integration, the custom `tenant`/`clearance` claims (Stories 03–06)
- *Authorization*: RBAC via JWT authorities across all three annotation styles, ownership checks,
  the ABAC `PermissionEvaluator` with its one deliberate escape hatch, and the layered
  app-level-plus-mesh-level service-to-service check (Stories 03–05, 09)
- *Mesh / edge (Istio)*: STRICT mTLS, JWT validation at gateway and service, per-service
  `AuthorizationPolicy` by SPIFFE identity, the `/internal/**` route that's unreachable by
  construction (Story 09)
- *Cross-cutting*: RFC 7807 errors, `@Audited` logging, hardened headers, demo secrets clearly
  marked (Story 02, threaded through every service)

**"Known simplifications (called out, not hidden)"** — be explicit and honest, matching what each
earlier story already flagged inline: `ddl-auto: update` instead of migrations (Stories 03–05);
plaintext demo credentials in Secrets/ConfigMaps/`application.yml` (Stories 06, 08); `web-app`'s
ROPC flow left on purely for `curl`-based demoing (Story 06); HTTP-only Istio `Gateway` (Story 09);
and a note that the Kubernetes/Istio manifests should be validated with `kubectl kustomize` /
`istioctl analyze` before trusting them against a real cluster if you haven't run Stories 08/09's
apply steps yourself in this environment.

**"Running it"** — two options, reusing commands you've already run and verified rather than
inventing new ones:
- *Option A — Docker Compose*: the exact `docker compose up --build` flow and port table from
  [Story 07](07-docker-compose-local-dev.md)
- *Option B — Kubernetes + Istio*: the full numbered walkthrough (create cluster → install Istio →
  build/load images → apply manifests → wait for rollout → verify mesh security → port-forward and
  reach the gateway → tear down) from [Story 08](08-kubernetes-base-manifests.md) and
  [Story 09](09-istio-service-mesh-security.md) — copy the exact commands you already validated,
  don't retype them from memory

**"Test users"** — the table from [Story 06, Task 5](06-keycloak-realm-setup.md) (username,
password, roles, tenant, clearance), plus the one-line note about
`service-account-order-service`/`SERVICE`.

**"Example requests"** — the five-request curl walkthrough from
[Story 07, Task 4](07-docker-compose-local-dev.md) (get a token, read own profile, create an
order, attempt cross-tenant document access, partner API-key access).

**"Testing"** — `./gradlew test`, plus the one-paragraph explanation of *how* these services are
tested: MockMvc requests through the real filter chain using
`SecurityMockMvcRequestPostProcessors.jwt()` to construct tokens with specific roles/claims
(never mocking Spring Security itself), with `common-security` and `admin-service` additionally
carrying pure unit tests for the JWT role mapping and the ABAC evaluator in isolation.

### Task 4 — Cross-check the README against the actual repo

Once written, walk it top to bottom and verify every command actually still works exactly as
written — port numbers match each `application.yml`'s `server.port`, every file path mentioned
exists, every test-user credential matches `keycloak/realm-export.json`, every curl command's
expected outcome matches what you observed running Stories 07 and 09 yourself. A README that
describes a slightly different system than the one in the repo is worse than no README.

## Definition of done

- [ ] `./gradlew test` passes with zero failures across all four modules
- [ ] Test counts meet or exceed the table in Task 2
- [ ] `README.md` exists at the repo root with all eight sections from Task 3
- [ ] Every command in the README has actually been run successfully at least once (Stories 07/09)
- [ ] Every credential, port, and path in the README matches the actual files in the repo

## What NOT to do yet

Don't write the `docs/` diagram-based documentation tree yet — that's a distinct, more visual
deliverable covered in [Story 11](11-documentation-and-diagrams.md), which builds on top of this
README rather than replacing it.
