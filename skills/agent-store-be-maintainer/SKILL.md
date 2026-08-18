---
name: agent-store-be-maintainer
description: Maintain AgentStore Kotlin/Spring backend code using eco-knock-be-central domain layering, JPA/Flyway, runtime execution, SSE, and payment boundaries.
---

# AgentStore BE Maintainer

Read `AI.md` first. Apply the HIGH_RISK matrix before migration, persistence, execution, SSE, payment, recovery, or OpenAPI work.

## Style

- Use Kotlin for controllers, services, DTOs, configuration, clients, and repository interfaces.
- Use Java for JPA entities, entity value objects, entity enums, and low-level security infrastructure.
- Use role packages: `controller`, `service`, `repository`, `dto/request`, `dto/response`, `model/entity`, `model/vo`, `client`, `resolver`, `runner`, `executor`, `config`, `event`.
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
