---
name: agent-store-be-style-verifier
description: Independently verify AgentStore Kotlin/Spring readability, layering, configuration, Flyway, runtime, SSE, and payment invariants without editing.
---

# AgentStore BE Style Verifier

Read `AI.md`, `HANDOFF.md`, this skill, the maintainer skill, and the closest reference precedent.
Review only developer-owned diff paths. Never edit, format, install, generate, migrate, update a
snapshot, or start the app. Read-only style/classes/tests/bootJar/diff checks are allowed. Compare
`git status --short` before and after commands.

Require the original requirement, risk classification, invariants, HIGH_RISK failure matrix and
row-to-test mapping, owned/pre-existing paths, contract/schema changes, checks, assumptions, and
unrun checks. Missing HIGH_RISK rows are blocking.

Review in three passes:

1. Check state transitions, trust boundaries, transaction/lock order, readiness, payment crash
   windows, recovery, callback/terminal races, and SSE replay/live lifecycle.
2. Map matrix rows to diff/tests; inspect package roles, controller thinness, cross-domain repository
   access, transaction ownership, JPA/Flyway, OpenAPI, stale paths, and dirty-path preservation.
3. Run `gradlew.bat detektMain`, then independently inspect the complete owned diff. Gradle no longer
   contains repository-specific regex or migration-hash gates, so verify the maintainer rules directly.
   Named arguments are required; only convention-identical annotations are redundant.

## Blocking structural and style rules

- `service` owns use cases/domain decisions; `client` owns transport. Prefer private service
  functions for cohesive one-use decisions. Reject both catch-all classes and invented one-use
  `Policy`/`Evaluator`/`Verifier`/`Helper` layers.
- Reject snowball changes that duplicate one catalog, schema, status, configuration, policy, or
  runtime state across layers or repositories. Require one authoritative owner and derived or
  generated projections. A large class split into one-use wrappers without reducing behavior remains
  a blocking complexity finding.
- Reject speculative weights, thresholds, fallback modes, lifecycle states, switches, or extension
  interfaces without a current caller and observable product behavior. When standard platform or
  framework functionality materially removes owned code, require justification for maintaining a
  custom implementation; do not require a larger dependency for a trivial replacement.
- Controllers bind related query parameters through validated request DTOs. Wire enum values are
  lowercase snake_case. Check OpenAPI and frontend consumers for every contract change.
- Every Kotlin function has a block body. Every companion is at the top and contains only constants,
  loggers, factories, or stateless converters.
- Kotlin calls/constructors with two or more arguments use named arguments. Java calls are the sole
  language-level exception and remain vertically formatted.
- Wildcard imports and body-qualified types/calls are blocking. Repeated byte literals use named
  constants. Unused helper parameters and one-line delegation wrappers are blocking.
- Required configuration has no constructor default and no `${ENV:default}` or `${ENV:-default}`.
  Standard datasource properties replace custom URL parsers. Only Flyway SQL belongs to production
  migration support; no production `common/migration` package or integration-profile YAML remains.
- Existing Flyway migrations are immutable: reject any edit to an existing migration and require a new
  migration. Reject reintroduced `common/migration`, `application-postgres-integration.yaml`, and
  `DatabaseUrlParser.kt` paths.
- `.env.example` contains only Spring datasource/runtime/x402/integration secrets plus Docker Compose
  PostgreSQL password and port. Public URL, wallet, policy, timeout, or payment-mode values belong in
  YAML, not `.env`.
- Common responses, exceptions, error codes, and the global exception handler are Kotlin. Internal
  DTOs end in `Dto`; HTTP types end in `Request` or `Response`.
- Remove redundant repository `@Param` and convention-identical entity `@Column(name)` metadata.
  Keep metadata that changes actual binding or schema semantics.
- Preserve blank-line breathing room. Dense validation/transformation/side-effect chains are a
  readability finding even if compilation succeeds.
- Public JSON uses the `CommonResponse<T>` envelope and trace correlation only in `X-Trace-Id`.
- Ordinary services never inject another domain repository. Controllers never inject repositories.
- Every JPA entity extends `BaseEntity`; moved classes do not remain duplicated at old paths.

Report every blocking finding together, grouped by invariant family:

`[severity] [family] file:line — invariant/matrix row — violated maintainer rule — adjacent paths reviewed — refactor scope — automatic-refactor: yes|no`

Finish with `PASS`, `REFRACTOR REQUIRED`, or `RISK REMAINS`. A second finding in one family requires
a family-level redesign; a third is a workflow failure, not waived.
