---
name: agent-store-be-maintainer
description: Maintain AgentStore Kotlin/Spring backend code using eco-knock-be-central domain layering, JPA/Flyway, runtime execution, SSE, and payment boundaries.
---

# AgentStore BE Maintainer

Read `AI.md` first. Apply the HIGH_RISK matrix before migration, persistence, execution, SSE, payment, recovery, or OpenAPI work.

## Style

- Use Kotlin for controllers, services, DTOs, configuration, clients, and repository interfaces.
- Use Java for JPA entities, entity value objects, entity enums, and low-level security infrastructure.
- Every domain must use the reference layout: `<domain>/controller`, `<domain>/dto`, `<domain>/model/entity`, `<domain>/model/vo`, `<domain>/repository`, and `<domain>/service`. Add `<domain>/resolver`, `<domain>/runner`, `<domain>/executor`, `<domain>/client`, `<domain>/event`, or `<domain>/config` only when that role exists.
- Request and response DTOs belong under `<domain>/dto/request` and `<domain>/dto/response` when they are HTTP-specific; internal projections belong under `<domain>/dto/internal`. Use the reference naming convention (`*DTO`, `*Request`, `*Response`).
- `service` contains use-case services only. A resolver, calculator, validator, runner, executor, client, mapper, graph node, or result value must never be placed under `service` merely because it is called by a service.
- Put graph/calculation result types in `model/vo` or `dto/internal`, one primary type per file. Do not create a catch-all `*Utils`, `*Helper`, `*Manager`, or `*Support` class.
- Keep controllers thin in the reference style: request binding, OpenAPI annotations, `ResponseEntity`/common response conversion where the contract requires it, and one service call. Business branching belongs in services/resolvers.
- Avoid JPA `@ManyToOne` associations. Store foreign keys as scalar UUID fields on entities and resolve related data through repository queries or an explicit service/resolver. Do not reintroduce entity graphs to bypass this boundary.
- One primary class per file; DTOs may group closely related request/response types.
- Use constructor injection. Do not use field injection, broad `Manager`/`Helper` abstractions, or direct entity serialization.
- Controllers bind HTTP and delegate. `@Transactional` belongs on services or explicit orchestration boundaries.
- A service may use only its own repositories. Cross-domain operations go through public services; use an orchestrator to prevent circular dependencies.

## Persistence and contract rules

- Flyway owns migrations; Hibernate uses `ddl-auto=validate`.
- Preserve the existing Prisma physical schema and API contract.
- Map PostgreSQL `BIGINT` atomic amounts to `BigInteger` and expose strings.
- Map JSONB deliberately with Jackson `JsonNode` or explicit collections.
- Use named PostgreSQL enum mapping and LAZY relations.
- Keep error shape `{ code, message, details?, traceId }` and hide runtime callback routes from OpenAPI.
- Springdoc `/openapi.json` is generated; never hand-edit it.

## High-risk invariants

- ACTIVE versions are immutable; dependency cycles, max depth, max steps, and max calls remain enforced.
- Create durable payment intent and budget reservation before an external side effect.
- Preserve journal, transaction hash, reservation, actual cost, and reconciliation state across every crash window.
- Authenticate callbacks before state mutation. Terminalization must be atomic, never check-then-write.
- SSE stores events before publishing, replays by sequence, deduplicates replay/live races, and closes after terminal events.
- x402 private keys and signed payloads stay in the official Node bridge; Spring receives only typed results.

## Verification

Use deferred clients/barriers for payment and callback races, PostgreSQL row-lock tests for budget reservation, restart/reconciliation fixtures, and SSE replay tests. Run the narrowest Gradle test first, then `classes`, `test`, `bootJar`, migration validation, and OpenAPI parity. Do not declare completion; return a full handoff.
