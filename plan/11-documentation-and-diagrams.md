# Story 11 — Diagram-First Documentation

**Depends on:** [Story 10 — Security Test Suite & README](10-security-test-suite-and-readme.md)
**Unlocks:** nothing — this is the last story in the sequence

## Goal

Produce the visual, diagram-first documentation layer on top of the README: an architecture
overview, request-flow sequence diagrams, the authorization model, the defense-in-depth security
layers, deployment topology, data model, and a module reference — the kind of documentation that
lets someone understand the *shape* of the system in minutes rather than by reading source files.

## Context

This is the one story that's different in kind from the other ten: it's not building new
application behavior, it's explaining behavior that already exists once Stories 00–10 are done.
Rather than re-deriving that content from scratch, use the actual finished implementation you just
built (Stories 02–09) as your source material, and hold yourself to the same standard of accuracy
this whole plan has used throughout: every diagram and claim should trace back to a real file and
real behavior in the services you built, not an idealized description of what they're "supposed"
to do.

If your repository already has a `docs/` tree and a companion interactive page (this project's own
finished version does — check for one before starting), treat it as the acceptance-criteria
reference for this story rather than starting from a blank page: reproduce its structure and
level of accuracy against *your own* implementation, rather than copying its prose verbatim
(your service package names, exact class layouts, and any deliberate variations you made along the
way should be reflected here, not the reference's).

## Tasks

### Task 1 — Architecture overview

One system-context diagram (who calls what, through which front door — client/partner →
gateway → the three services → Keycloak/Postgres) and one container/module diagram (how
`common-security`'s beans compose into each service). Ground every arrow in a real route rule or
config from Stories 03–05, not a guess.

### Task 2 — Request flows

Sequence diagrams for the four authentication mechanisms this system actually implements: OIDC
login + resource read (Story 03), the client-credentials cross-service call (Story 04), the ABAC
document-access decision (Story 05), and the API-key partner flow (Story 05). Base each diagram
directly on the method bodies and filter chains you wrote — a sequence diagram that doesn't match
the actual call order in the code is worse than no diagram.

### Task 3 — Authorization model

RBAC vs. ABAC, the three method-security annotation shapes side by side with real examples from
Stories 03–05, a decision-flow diagram for `DocumentPermissionEvaluator.checkAccess` (Story 05,
Task 4) walked in its exact branch order, and a role/permission matrix for the five seeded users
(Story 06, Task 5).

### Task 4 — Security layers

The full defense-in-depth stack, mesh to method, for one request followed end to end — reuse
Story 09's own framing of layers 1–4 (mTLS, mesh JWT validation, mesh authorization, route
reachability) stacked on top of Stories 03–05's layers 5–8 (app JWT validation, route
authorization, method authorization, ABAC).

### Task 5 — Deployment

Diagrams for both deployment shapes: the Docker Compose topology (Story 07) and the Kubernetes +
Istio topology including sidecars (Stories 08–09), plus the Kustomize base/overlay relationship
between them.

### Task 6 — Data model

An entity diagram for `User`/`Order`/`Document` (Stories 03–05) that makes the "separate databases,
no cross-service joins" design explicit — `ownerId`/`tenantId` fields are resolved over HTTP
between services, never joined in SQL.

### Task 7 — Module reference

Per-module filter-chain composition diagrams (Story 02's beans flowing into each service's
`SecurityConfig`, Stories 03–05) plus a source map linking each concern to the file that
implements it.

### Task 8 — Publish

Commit the finished documentation tree to the repository (a `docs/` directory mirroring Tasks 1–7,
one file per topic, plus an index linking them) so it's versioned alongside the code it describes.
If your environment supports publishing an interactive companion page, that's a reasonable
second form of the same content — but the versioned, source-linked files in the repo are the
one required deliverable; a good stopping point for anyone continuing this project without one.

## Definition of done

- [ ] Every diagram traces to a real file/behavior in the finished Stories 00–10 implementation
- [ ] The documentation is committed to the repository, not left as a local-only artifact
- [ ] A reader who has never seen the source can explain, from the docs alone, why a request to
      `/internal/users/{id}` from outside the cluster fails, and at which of the layers from
      Task 4 it fails

## What NOT to do yet

Nothing — this is the final story. Once this is done, the "build this entire application from
scratch" plan is complete: a fresh clone of the repository, built by following Stories 00–11 in
order, should be behaviorally and structurally equivalent to the finished project.
