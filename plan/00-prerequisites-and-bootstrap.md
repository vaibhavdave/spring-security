# Story 00 — Prerequisites & Repository Bootstrap

**Depends on:** nothing — this is the starting point.
**Unlocks:** [Story 01 — Gradle Multi-Module Scaffold](01-gradle-multi-module-scaffold.md)

## Goal

Get a laptop ready to build, run, and test the whole system before writing a single line of
application code, and create the empty git repository the rest of the stories build inside.

## Context

The finished project is a 4-module Gradle/Spring Boot build that runs standalone, in Docker
Compose, or in a local `kind` Kubernetes cluster with Istio. You won't need Kubernetes or Istio
until [Story 08](08-kubernetes-base-manifests.md)/[Story 09](09-istio-service-mesh-security.md),
but installing everything now avoids a mid-project tooling detour.

## Tasks

### Task 1 — Install required tooling

| Tool | Version used by this project | Check with |
|---|---|---|
| JDK | 21 (Temurin recommended) | `java -version` |
| Docker | any recent version, with Compose v2 | `docker compose version` |
| Git | any recent version | `git --version` |
| `kind` | latest ([install guide](https://kind.sigs.k8s.io/)) | `kind version` |
| `kubectl` | matching your target cluster | `kubectl version --client` |
| `istioctl` | latest ([install guide](https://istio.io/latest/docs/setup/getting-started/#download)) | `istioctl version --remote=false` |
| `jq` | any (used in later curl examples) | `jq --version` |

You do **not** need to install Gradle — the project ships its own wrapper (`./gradlew`), added in
Story 01, which downloads the pinned Gradle version automatically.

**Acceptance criteria:** every command in the right-hand column above runs without a
"command not found" error.

### Task 2 — Create the repository

```bash
mkdir enterprise-security-microservices
cd enterprise-security-microservices
git init
```

### Task 3 — Add `.gitignore`

Create `.gitignore` at the repo root with:

```gitignore
.gradle/
build/
out/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
.vscode/
.DS_Store
HELP.md
*.log
```

The `!gradle/wrapper/gradle-wrapper.jar` negation matters: everything else under `build/`/`.gradle/`
is disposable, but the wrapper jar itself must be committed so a fresh clone can run `./gradlew`
without first having Gradle installed.

### Task 4 — First commit

```bash
git add .gitignore
git commit -m "Initial commit: empty repository with .gitignore"
```

## Definition of done

- [ ] `java -version` reports a JDK 21 toolchain is available (via `sdkman`, your OS package
      manager, or a direct Temurin install)
- [ ] `docker compose version` succeeds
- [ ] `kind`, `kubectl`, `istioctl` are on `PATH` (even though they're unused until Story 08/09)
- [ ] Repo exists locally with one commit containing only `.gitignore`

## What NOT to do yet

Don't create any Gradle files, Java source, or Docker files in this story — that's
[Story 01](01-gradle-multi-module-scaffold.md) onward. This story is tooling and an empty repo
only.
