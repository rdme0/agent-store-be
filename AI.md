# AI.md

## Skills

- `agent-store-be-maintainer`: Maintain AgentStore Kotlin/Spring code in the established domain-layered style.
- `agent-store-be-style-verifier`: Independently review Spring production diffs without editing them.
- `api-doc-maintainer`: Keep Springdoc/Scalar and the public OpenAPI contract aligned.
- `readme-maintainer`: Keep setup, profiles, bridge, and run commands synchronized.
- `git-commit-korean`: Create small Korean commits matching repository history.

## Local guidance

- This repository is the Kotlin/Spring migration target. The old Fastify/Prisma repository is a read-only compatibility reference.
- Preserve the public AgentStore API and PostgreSQL data. Do not run Spring and TypeScript API writes against the same database.
- Read the relevant skill before changing files in its scope. Record pre-existing dirty paths first.
- Follow `eco-knock-be-central` style: Kotlin services/controllers/DTOs/repository interfaces, Java JPA entities/VOs/enums, constructor injection, thin controllers, transactional services, and role packages.

## Risk classification and preflight

Migration work is `HIGH_RISK` because it crosses database, transaction, concurrency, restart, SSE, payment, and OpenAPI boundaries. Before production implementation, record the changed invariants, state transitions, trust boundaries, and a complete failure matrix covering:

| Phase | Failure or race | Durable state | Reservation/cost action | Retry/idempotency | User-visible outcome | Test |
|---|---|---|---|---|---|---|

Include side-effect-before/after boundaries, Flyway baseline mismatch, external payment after signature, journal failure, restart reconciliation, callback/terminal races, SSE replay/live races, and duplicate requests. Mark an inapplicable row explicitly.

## Mandatory workflow

1. Record dirty paths, risk class, contract ownership, and failure matrix.
2. Developer uses `$agent-store-be-maintainer`, implements only its owned slice, and runs checks.
3. Developer submits a handoff with requirement, risk, invariants, matrix/test mapping, owned files, exact diff, contract changes, commands/results, assumptions, and no completion claim.
4. Fresh verifier uses `$agent-store-be-style-verifier` and receives the requirement, matrix, current diff, and dirty-path boundary—not the developer's conclusion.
5. Verifier reviews the complete diff, groups all blockers by invariant family, and may run only safe non-rewriting checks.
6. Developer audits the whole family and adjacent states, updates code, matrix, and tests. A second blocker in one family requires lifecycle/state-machine redesign. A third is recorded as a workflow failure, but completion still requires a fresh verifier PASS.
7. Repeat with fresh verifiers until zero blocking findings. There is no cycle cap.

Parallelize only when files, contracts, invariants, and runtime state are independent. Serialize schema, migration, OpenAPI, execution state, and payment boundaries.
