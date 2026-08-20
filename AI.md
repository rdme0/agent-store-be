# AI.md

## Local skills

- `skills/agent-store-be-maintainer/SKILL.md`: production implementation rules.
- `skills/agent-store-be-style-verifier/SKILL.md`: fresh read-only structural and invariant review.
- `skills/api-doc-maintainer/SKILL.md`: Springdoc `/openapi.json` and REST/SSE contract, including the CommonResponse envelope.
- `skills/readme-maintainer/SKILL.md`: setup/profile/bridge documentation.
- `skills/git-commit-korean/SKILL.md`: Korean commit conventions when a commit is explicitly requested.

Read the relevant skill before changing its scope. `AGENTS.md` is only the entry point to this file.

## Repository contract

This repository is the Kotlin/Spring AgentStore runtime. Preserve the existing REST/SSE paths and status codes, while
using the intentional `CommonResponse<T>` JSON envelope (`isSuccess`, `message`, `errorCode`, `result`) for every
public JSON response. Trace correlation is an `X-Trace-Id` response header and MDC value, never a JSON field. Preserve
PostgreSQL schema/data, Flyway history, ACTIVE-version immutability, dependency limits,
execution/payment/recovery state machines, callback authentication, SSE replay, and the Node-only x402 signing boundary.
Spring and the former TypeScript API must never write the same database concurrently. Preserve pre-existing dirty paths
and never edit the read-only `eco-knock-be-central` reference.

## Workflow

1. Record all dirty paths and classify the change as `STANDARD` or `HIGH_RISK`.
2. For `HIGH_RISK` work (schema/Flyway, transaction/lock, execution/callback, payment/recovery, SSE, OpenAPI), write
   invariants, trust boundaries, state transitions, a failure matrix, and row-to-test mapping before implementation.
3. Developer uses the maintainer skill, implements only the owned slice, runs narrow checks, and submits a handoff
   without declaring completion.
4. A fresh verifier receives the requirement, matrix, current diff, and dirty-path boundary—not the developer's
   conclusion—and reviews read-only.
5. Findings are grouped by invariant family. The developer audits adjacent states and updates code, matrix, and tests
   together; repeat with a fresh verifier until blocking findings are zero. There is no fixed cycle cap.
6. Parallel work is allowed only when files, contracts, invariants, and runtime state are independent. Serialize shared
   schema, OpenAPI, execution state, payment, and recovery boundaries.

`STANDARD` CRUD, wording, and isolated style changes do not require a full failure matrix, but they still follow
developer → fresh verifier and preserve contracts.
