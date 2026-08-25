# Documentation

Visual, diagram-first documentation of the **Enterprise Security Microservices** project — how
the four modules fit together, how a request actually flows through them, and why the same check
sometimes shows up at more than one layer. Start at the top and work down, or jump straight to
what you need:

| # | Page | What's in it |
|---|---|---|
| 1 | [Architecture Overview](01-architecture-overview.md) | System context and container diagrams — who calls what, and through which front door |
| 2 | [Request Flows](02-request-flows.md) | Sequence diagrams for all four auth mechanisms: OIDC login, client-credentials, ABAC document access, API-key partner calls |
| 3 | [Authorization Model](03-authorization-model.md) | RBAC vs ABAC, the three method-security annotations compared, the `DocumentPermissionEvaluator` decision flowchart, the role/permission matrix |
| 4 | [Security Layers](04-security-layers.md) | The full defense-in-depth stack, mesh to method, for one request followed end to end |
| 5 | [Deployment](05-deployment.md) | Docker Compose vs Kubernetes+Istio, pod/sidecar diagrams, the Kustomize overlay structure |
| 6 | [Data Model](06-data-model.md) | Entity-relationship diagram across the three owning databases |
| 7 | [Module Reference](07-module-reference.md) | Per-module filter-chain composition diagrams, with a source-file map |

All diagrams are [Mermaid](https://mermaid.js.org/) and render natively on GitHub. For a single
polished visual walkthrough of the same material, see the companion Artifact linked from the
[project README](../README.md).

## Reading order by goal

- **"I just want the shape of the system"** → 1 → 5
- **"I want to understand the authorization logic specifically"** → 3 → 4
- **"I'm about to modify a service's security config"** → 7, then the relevant section of 4
