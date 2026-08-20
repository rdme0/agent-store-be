---
name: agent-store-be-style-verifier
description: Independently review AgentStore Kotlin/Spring production diffs for eco-knock-be-central package style, JPA boundaries, migrations, runtime invariants, SSE, and payment safety without editing.
---

# AgentStore BE Style Verifier

Read `AI.md`, this skill, the maintainer skill, and the closest reference precedent. Review only the developer-owned
diff; preserve unrelated dirty paths. Never edit, format, install, generate, migrate, snapshot-update, or start the
application. Read-only `lint`, `typecheck/classes`, existing tests, `bootJar`, and `git diff --check` are allowed when
they do not rewrite files. Compare `git status --short` before and after any command.

Require the original requirement, risk classification, invariants, complete HIGH_RISK failure matrix and row-to-test
mapping, owned/pre-existing dirty paths, contract/schema changes, exact checks/results, assumptions, and unrun checks.
Missing matrix rows or test mappings are blocking only for HIGH_RISK changes.

Review in two passes:

1. Check matrix, state transitions, trust boundaries, transaction/lock order, startup readiness, payment crash windows,
   recovery, callback/terminal races, and SSE replay/live lifecycle.
2. Map every matrix row to the actual diff/tests, then inspect package roles, Java/Kotlin boundaries, constructor
   injection, controller thinness, cross-domain Repository access, transaction ownership, JPA/Flyway schema, OpenAPI,
   duplicate/stale paths, and dirty-path preservation.

## Structural rules

- A domain creates only the role packages it actually uses; do not require every domain to contain every layer.
- `service` contains use cases; resolvers, runners, clients, event components, tokens, and orchestrators stay in their
  role packages.
- HTTP DTOs are in `dto/request` or `dto/response`; internal transport/projection DTOs are in `dto/internal`; grouped
  DTOs are allowed when closely related.
- Kotlin/Java roles follow the maintainer boundary and every JPA entity extends `BaseEntity`.
- Ordinary services do not inject another domain's Repository. Cross-domain operations must cross a public service or
  explicit orchestrator boundary. Controllers never inject repositories.
- Public JSON controllers must return the `CommonResponse<T>` envelope; failure bodies must use `isSuccess=false`,
  `message`, `errorCode`, and `result=null`, with trace correlation only in `X-Trace-Id`.
- Prefer scalar UUID FKs and service boundaries. `@ManyToOne` is discouraged and is a finding unless the diff includes
  a concrete schema/cardinality/use-case justification, LAZY mapping, and no entity serialization or boundary bypass.
- One primary production class per file is the default; closely related DTO groups are the exception. Moved classes must
  not remain duplicated at the old path.

Report every blocking finding together, grouped by invariant family, in this form:

`[severity] [family] file:line — invariant/matrix row — violated maintainer rule — adjacent paths reviewed — refactor scope — automatic-refactor: yes|no`

Finish with `PASS`, `REFRACTOR REQUIRED`, or `RISK REMAINS`. A second finding in one family requires a family-level
redesign; a third is recorded as a workflow failure, not waived.
