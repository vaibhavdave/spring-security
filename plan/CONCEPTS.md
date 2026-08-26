# Concepts Primer — Background for a Junior/Inexperienced Engineer

This plan (Stories 00–11) is written to be precise about *what* to build and *why*, but it assumes
you already know what a JWT is, how Spring Security's filter chain works, what a Kubernetes
`Deployment` does, or what a service mesh sidecar is. If any of those are new to you, read the
relevant section below **before** starting the story that introduces it — each section names which
story needs it. This file doesn't replace any story's own "Context" section; it's the layer
underneath, so the story's explanation actually lands instead of being a wall of unfamiliar terms.

You don't need to read this file straight through. Skim the table of contents, read what you don't
already know, and come back to a section when a story references it.

## Table of contents

1. [Tokens & identity: JWT, bearer tokens, claims](#1-tokens--identity-jwt-bearer-tokens-claims) — needed for Story 02+
2. [OAuth2 and OIDC: the grants this project actually uses](#2-oauth2-and-oidc-the-grants-this-project-actually-uses) — needed for Story 02, 04, 06
3. [RBAC vs. ABAC](#3-rbac-vs-abac) — needed for Story 03, 05
4. [Spring Security building blocks](#4-spring-security-building-blocks) — needed for Story 02–05
5. [SpEL cheat sheet (the expressions inside `@PreAuthorize`)](#5-spel-cheat-sheet) — needed for Story 03–05
6. [AOP in one paragraph (why `AuditLogAspect` looks the way it does)](#6-aop-in-one-paragraph) — needed for Story 02
7. [Gradle multi-module builds](#7-gradle-multi-module-builds) — needed for Story 01
8. [Containers & Docker basics](#8-containers--docker-basics) — needed for Story 07
9. [Kubernetes core objects](#9-kubernetes-core-objects) — needed for Story 08
10. [Kustomize: base + overlay](#10-kustomize-base--overlay) — needed for Story 08–09
11. [Service mesh & Istio](#11-service-mesh--istio) — needed for Story 09
12. [The "layers" mental model for this whole project](#12-the-layers-mental-model-for-this-whole-project) — useful from Story 03 onward
13. [Glossary of terms and acronyms used without expansion](#13-glossary)

---

## 1. Tokens & identity: JWT, bearer tokens, claims

A **JWT** (JSON Web Token) is a compact, signed piece of text that carries a set of claims — a JSON
object — about who the caller is and what they're allowed to do. It has three base64-encoded parts
separated by dots: `header.payload.signature`. The payload is the part you actually care about in
this project; it's plain JSON once decoded (that's exactly what Story 06's verification step does
with `cut -d. -f2 | base64 -d`). The signature is what lets a service trust the payload without
calling back to whoever issued it — anyone holding the issuer's public key can verify the signature
wasn't tampered with.

A **claim** is just one key/value pair in that payload — `sub` (subject, i.e. "who"), `iss`
(issuer, i.e. "who signed this"), `exp` (expiry), and, in this project, two **custom claims**
Keycloak is configured to add: `tenant` and `clearance` (Story 06). "Custom" just means they're not
part of any spec — Keycloak lets you attach arbitrary attributes to a user and expose them as
claims via a protocol mapper.

A **bearer token** is the delivery mechanism: the client sends the JWT in an HTTP header,
`Authorization: Bearer <token>`, and *whoever holds the token* is treated as authenticated —
there's no additional proof of identity beyond possessing the string. That's why every service in
this project runs over HTTPS/TLS in a real deployment (Istio's mTLS in Story 09 covers the
in-cluster hops) — a bearer token that leaks in transit is fully usable by whoever intercepts it.

**Resource server** is the term for a service that *validates* JWTs but never issues them —
`user-service`, `order-service`, and `admin-service` are all resource servers for inbound requests.
Validating means: checking the signature against the issuer's public keys (fetched from a **JWKS**
endpoint — JSON Web Key Set — which is why `issuer-uri`/`jwk-set-uri` appear in every
`application.yml`), checking `exp` hasn't passed, and checking `iss` matches who you expect.

## 2. OAuth2 and OIDC: the grants this project actually uses

**OAuth2** is a framework for one party obtaining a token that authorizes it to act, without ever
handling another party's password. **OIDC** (OpenID Connect) is a thin identity layer on top of
OAuth2 that standardizes how you get *who the user is*, not just an opaque access token — Keycloak
speaks OIDC, which is why every issuer URL in this project has `/protocol/openid-connect/` in it.

OAuth2 defines several **grant types** (ways to obtain a token); this project deliberately uses
exactly two, and only two, on purpose — don't be surprised the plan never mentions the others:

- **Authorization Code grant** (and its close cousin, the **Resource Owner Password Credentials /
  ROPC grant**, `grant_type=password`) — a *human* proves their identity (browser redirect for the
  real Authorization Code flow; a direct username/password POST for ROPC) and gets back a token
  representing *them*. Story 06's `web-app` client is configured for this — ROPC is turned on
  there purely so `curl -d grant_type=password` works for demoing without a browser; the story's
  own text flags that a real deployment would turn ROPC off and keep only the browser-based flow.
- **Client Credentials grant** — a *service* proves its own identity (client ID + secret, no human,
  no user password involved at all) and gets back a token representing *itself*. This is what
  Story 04's `order-service` uses to call `user-service`'s `/internal` API — the token's subject is
  the service account, not any user, which is why Story 06 creates a dedicated
  `service-account-order-service` user with only the `SERVICE` role.

Everything else in this project (validating a token, reading its claims) is the same regardless of
which grant produced the token — a resource server can't tell (and doesn't need to) whether the
JWT it's validating came from a human login or a service's own client-credentials exchange, beyond
what the claims themselves say.

## 3. RBAC vs. ABAC

**RBAC** (Role-Based Access Control) answers "does this caller have role X?" — `hasRole('ADMIN')`,
`@Secured({"ROLE_ADMIN"})`. It's coarse and cheap to check, and it's what Stories 03 and 04 use
almost exclusively (plus **ownership checks** — "is this caller the owner of the specific record
they're touching," which is a slightly finer-grained cousin of RBAC, not full ABAC).

**ABAC** (Attribute-Based Access Control) answers a richer question: "given *this specific
caller's* attributes (tenant, clearance) and *this specific resource's* attributes (tenant,
classification), is the action allowed?" It's not about which role you hold at all, until every
attribute check has already passed. Story 05's `DocumentPermissionEvaluator` is the one place in
this project that does this — read its own Task 4 walkthrough closely; the *order* the checks run
in is the entire point (tenant isolation and clearance are checked before any role is even looked
at).

A useful way to tell them apart while reading the code: an RBAC check only ever needs
`authentication.getAuthorities()`. An ABAC check needs to compare something about the caller
(a claim) against something about the specific object being acted on (a loaded entity's field) —
that's why `DocumentPermissionEvaluator` takes both an `Authentication` and either a loaded
`Document` or an id to load one.

## 4. Spring Security building blocks

Two layers of Spring Security show up throughout Stories 02–05, and it's worth being clear they're
different mechanisms solving different problems:

- **The `SecurityFilterChain`** (each service's `SecurityConfig`, e.g. Story 03 Task 6) — a chain
  of servlet filters that runs on *every* HTTP request before it reaches a controller. This is
  where coarse, route-based rules live (`.requestMatchers("/internal/**").hasAnyRole(...)`), where
  the incoming JWT actually gets parsed and validated (`.oauth2ResourceServer(...)`), and where
  cross-cutting filters get inserted (`TenantContextFilter`, `ApiKeyAuthFilter` — both built in
  Story 02). Order matters here: `.addFilterBefore(...)` / `.addFilterAfter(...)` position a filter
  relative to a named built-in filter class, and that relative order is what makes, for instance,
  Story 05's dual JWT/API-key auth work without either scheme interfering with the other.

- **Method security** (`@PreAuthorize`, `@PostAuthorize`, `@Secured`, on individual service methods,
  e.g. Story 03 Task 4) — a separate mechanism, enabled once for the whole project by
  `@EnableMethodSecurity` in Story 02's `CommonSecurityAutoConfiguration`, that wraps annotated
  methods with an authorization check evaluated *at the point the method is called*, not at the
  HTTP layer. This is where fine-grained, per-record decisions live — "is this specific caller
  allowed to touch this specific loaded object."

`@PreAuthorize` runs its check **before** the method body executes, using only what's available
from the arguments (`#id` binds a method parameter). `@PostAuthorize` runs its check **after** the
method body has already executed and returned a value, so its expression can reference
`returnObject` — the actual loaded entity. That's why Story 03's `getUser` uses `@PostAuthorize`
(it needs the loaded user's `tenantId`, which isn't known until the row is actually fetched) while
`updateUser` uses `@PreAuthorize` (the `#id` path variable alone is enough to decide).

Both mechanisms can — and in this project routinely do — enforce overlapping rules on the same
request (e.g. `/internal/**` is gated at the route level *and* the method level in Story 03). That
redundancy is deliberate, not an oversight; see [section 12](#12-the-layers-mental-model-for-this-whole-project).

## 5. SpEL cheat sheet

The strings inside `@PreAuthorize(...)` / `@PostAuthorize(...)` are **Spring Expression Language**
(SpEL), a small expression language, not Java. The handful of pieces this project actually uses:

| Expression | Means |
|---|---|
| `hasRole('ADMIN')` | caller has authority `ROLE_ADMIN` (the `ROLE_` prefix is added implicitly by `hasRole`) |
| `hasAnyRole('SERVICE', 'ADMIN')` | caller has at least one of the listed roles |
| `#id` | binds to the method parameter named `id` — requires `-parameters` at compile time (Story 01) |
| `authentication.name` | the caller's principal name — for a JWT, this is the `sub` claim |
| `authentication.token.claims['tenant']` | reads an arbitrary claim directly off the caller's JWT |
| `returnObject.tenantId` | (only valid in `@PostAuthorize`) a field on the value the method just returned |
| `hasPermission(#id, 'Document', 'READ')` | delegates to a custom `PermissionEvaluator` bean (Story 05) — the string `'Document'` and `'READ'`/`'WRITE'` are arbitrary strings your own evaluator interprets, not a Spring-defined vocabulary |

Expressions combine with `or`/`and` exactly like you'd expect (`hasRole('ADMIN') or #id ==
authentication.name`). If you're ever unsure what an expression evaluates against, the answer is
always "the same `Authentication` object that's in `SecurityContextHolder` for this request" — for
every service in this project, that means a `Jwt`-backed authentication (or, in Story 05's
partner-API-key path, an `ApiKeyAuthenticationToken`).

## 6. AOP in one paragraph

**AOP** (Aspect-Oriented Programming) is a way to run code "around" a method call without that
method knowing about it — Story 02's `AuditLogAspect` uses it so that adding `@Audited` to any
service method (in Stories 03–05) gets audit logging for free, with zero code inside the method
itself. `@Around("@annotation(audited)")` means "wrap every method call that carries `@Audited`";
inside, `joinPoint.proceed()` is the actual original method call — everything before it runs
before the method, everything after runs after (or, on an exception, in the `catch`/`finally`
instead). You don't need to know AOP beyond this to work with the aspect Story 02 asks you to
build — it's a fixed, small pattern, not something you need to generalize.

## 7. Gradle multi-module builds

A **multi-module** Gradle build is one root project (`settings.gradle.kts` lists the modules) that
produces several build outputs from one source tree — here, one shared library
(`common-security`, a plain jar) and three independently-runnable applications (`user-service`,
`order-service`, `admin-service`, each an executable "boot jar"). Shared build configuration
(Java version, test framework, the Spring Boot version) is declared **once**, in the root
`build.gradle.kts`'s `subprojects { ... }` block (Story 01), so individual modules only declare
*their own* dependencies, not the whole toolchain setup again. `implementation(project(":common-security"))`
(seen in Stories 03–05) is how one module depends on another module in the *same* build, as
opposed to an external library from Maven Central.

The **Gradle wrapper** (`./gradlew`) is a small script + jar, committed to the repo, that downloads
and runs the exact pinned Gradle version for you — it's why Story 00 says you don't need Gradle
installed globally, and why Story 01's first task is generating and committing it.

## 8. Containers & Docker basics

A **Docker image** is a packaged, runnable snapshot of an application plus everything it needs to
run (a JRE, in this project's case) — built from a `Dockerfile`, a script of instructions. A
**multi-stage build** (every `Dockerfile` in Story 07) uses more than one `FROM` line: an early
stage does the heavy work (here, compiling and packaging the jar with a full JDK image) and the
*final* stage — the one that actually becomes the shipped image — copies only the finished
artifact into a much smaller runtime image. The build tooling itself (the JDK, Gradle's downloaded
dependencies) never ends up in the image you actually deploy.

**Docker Compose** (`docker-compose.yml`, Story 07) describes a group of containers that should run
together, on a shared private network, with dependency ordering (`depends_on`) and named-volume
persistence. Inside that network, containers reach each other **by service name** (`keycloak`,
`postgres`, `user-service`) exactly like a DNS hostname — never `localhost`, since `localhost`
inside a container refers to that container itself, not its neighbors.

## 9. Kubernetes core objects

Kubernetes runs containers too, but at a different scale and with a different vocabulary. The
objects this project's manifests (Story 08) actually use:

| Object | What it's for |
|---|---|
| `Namespace` | a named partition of the cluster — this project puts everything in one, `enterprise-security` |
| `Deployment` | describes a desired set of running container replicas (a "Pod template") and keeps that many running, restarting on failure |
| `Pod` | the actual running unit — one or more containers sharing a network namespace; you rarely write these by hand, `Deployment` creates them |
| `Service` | a stable network name + virtual IP in front of a set of Pods (selected by label), so callers never need to track individual Pod IPs, which change constantly |
| `ServiceAccount` | an identity a Pod runs *as*, inside the cluster — this project deliberately gives each of the three services its **own** `ServiceAccount` (not a shared default one), because Istio's `AuthorizationPolicy` (Story 09) keys its rules off exactly this identity |
| `Secret` / `ConfigMap` | key/value data mounted into Pods as env vars or files — `Secret` is conventionally for sensitive values, `ConfigMap` for non-sensitive ones; Kubernetes itself doesn't encrypt `Secret` contents by default, which is why Story 08 flags its `Secret` manifests as demo-only |
| `PersistentVolumeClaim` | a request for durable storage that survives a Pod restart — used for Postgres's data directory |

**Probes** (`readinessProbe`, `livenessProbe`) are periodic health checks Kubernetes runs against
each container: a failing *readiness* probe removes the Pod from a `Service`'s routing (it's up
but not ready for traffic yet); a failing *liveness* probe gets the container restarted (something
inside it has wedged).

## 10. Kustomize: base + overlay

**Kustomize** (built into `kubectl` as `kubectl apply -k`) composes plain YAML manifests without
templating — you write ordinary Kubernetes YAML, and a `kustomization.yaml` file lists which files
to include and any patches to layer on top. This project uses the **base + overlay** pattern: Story
08 produces `k8s/base/` (works with or without Istio installed), and Story 09 produces a *separate*
`k8s/istio/` overlay whose `kustomization.yaml` starts with `resources: [../base, ...]` and adds
Istio-specific resources on top, without modifying a single file inside `base/` itself. This is why
the plan repeatedly insists Story 09 is "layered on top," not "a rewrite of Story 08" — the base
manifests stay valid and reusable on their own even after the overlay exists.

`configMapGenerator` (used in Story 08 Task 6) is a Kustomize feature that builds a `ConfigMap`
directly from an existing file's contents at apply time, so the realm JSON (Story 06) and the DB
init script (Story 07) each have exactly one copy in the repo, reused by every deployment mechanism
instead of being pasted into a Kubernetes manifest by hand.

## 11. Service mesh & Istio

A **service mesh** adds a network-level layer of security and control between services, without
changing their application code. Istio does this by injecting a **sidecar** — a small proxy
container (`istio-proxy`) — into every Pod alongside your application container; all network
traffic in and out of the Pod actually flows through that proxy. That's why, after Story 09,
`kubectl get pods` shows `2/2` Ready instead of `1/1` — two containers per Pod, your app plus its
sidecar.

The specific mesh features this project turns on:

- **mTLS** (mutual TLS) — normally, TLS proves the *server's* identity to a client. *Mutual* TLS
  proves **both** directions: every pod-to-pod connection in the mesh presents a certificate, so
  each side cryptographically knows who it's actually talking to. Istio issues each pod a
  **SPIFFE** identity (a standard format for workload identities, `spiffe://cluster.local/ns/<namespace>/sa/<serviceaccount>`)
  derived automatically from its `ServiceAccount` — which is exactly why Story 08 insisted on a
  distinct `ServiceAccount` per service well before Istio ever enters the picture.
- **`PeerAuthentication`** — the Istio resource that turns mTLS on (and, with `mode: STRICT`,
  refuses any plaintext connection) for a namespace or workload.
- **`RequestAuthentication`** — validates a JWT presented in a request (same JWKS-based validation
  idea as section 1, just enforced by the mesh proxy instead of, or in addition to, the
  application). On its own it only *validates or lets through*; it doesn't require anything.
- **`AuthorizationPolicy`** — the resource that actually allows or denies a request, based on rules
  like "which SPIFFE identity is calling" and/or "does the validated JWT have this claim." This is
  the mesh-level analogue of Spring Security's route rules — a second, independent gate a request
  has to pass, enforced by infrastructure rather than application code.
- **`Gateway`** / **`VirtualService`** — how external traffic enters the mesh at all (`Gateway`) and
  how it gets routed to the right internal `Service` by path (`VirtualService`). A path with no
  route in the `VirtualService` isn't merely denied — there's nowhere for it to go, which is a
  stronger guarantee than an authorization rule (see Story 09's `/internal/**` discussion).

The reason this project layers mesh-level checks *on top of* the app-level checks from Stories
02–05, rather than replacing them, is the same "defense in depth" idea discussed next.

## 12. The "layers" mental model for this whole project

By the time you reach Story 09, a single request to (say) `/internal/users/{id}` has to pass
**multiple, independent** checks, at different layers, each of which could deny it for a different
reason:

1. Is there even a route to this path from outside the mesh? (Istio `VirtualService` — Story 09)
2. Does the mesh allow this caller's workload identity to reach this path? (Istio
   `AuthorizationPolicy` — Story 09)
3. Does the Spring app's route rule allow this? (`SecurityConfig`'s `authorizeHttpRequests` —
   Story 03)
4. Does the specific method being called allow this caller's role? (`@PreAuthorize` — Story 03)

None of these layers trusts the others — a bug or misconfiguration in any single one doesn't open
the whole system, because the remaining layers still apply. This redundancy is intentional
throughout the plan, called out explicitly wherever it appears (Story 03's Task 6, Story 09's Task
5) — if a check looks "already covered by an earlier story," that's usually the point, not a sign
you misread something.

## 13. Glossary

Short definitions for terms the stories use without re-explaining, in the order they first appear:

- **RFC 7807 (`ProblemDetail`)** — a standard JSON shape for HTTP error responses
  (`type`/`title`/`detail`/`instance`/`status`), used everywhere in this project instead of
  Spring's default HTML error page or an ad-hoc JSON shape, so every service's errors look the
  same to a client.
- **MDC (Mapped Diagnostic Context)** — a per-thread key/value map SLF4J logging frameworks expose,
  used by `AuditLogAspect` (Story 02) and the logging pattern in every `application.yml` to attach
  context (tenant, action) to individual log lines without passing it through every method
  signature.
- **`ThreadLocal`** — a Java mechanism for a variable that's separate per-thread. `TenantContext`
  (Story 02) uses one; the reason every filter that touches it *must* clean up in a `finally` block
  is that servlet containers reuse threads across unrelated requests, so a forgotten cleanup leaks
  one request's data into the next request that happens to land on the same thread.
- **Constant-time comparison** — comparing two secrets (Story 02's API key check) using
  `MessageDigest.isEqual` instead of `String.equals`, so the comparison takes the same amount of
  time regardless of where the strings first differ — defeats timing-based side-channel attacks
  that could otherwise let an attacker guess a secret one byte at a time.
- **DTO (Data Transfer Object)** — a plain class/record (like `UserResponse`, `RemoteUser`) used to
  shape data crossing a boundary (an HTTP response, a service-to-service call), kept deliberately
  separate from the JPA entity it's derived from so internal persistence details never leak
  directly onto the wire.
- **JPA / Hibernate entity** — a plain Java class annotated to map to a database table
  (`@Entity`/`@Table`/`@Column`); Spring Data JPA generates the actual SQL.
- **`ddl-auto: update`** — a Hibernate setting that auto-creates/updates database tables to match
  your entity classes. Convenient for a demo project; every story that uses it flags that a real
  deployment would use versioned migrations (Flyway/Liquibase) instead, since auto-updating schema
  in production is unpredictable and hard to review.
- **SPIFFE** — see [section 11](#11-service-mesh--istio).
- **Sidecar** — see [section 11](#11-service-mesh--istio).
