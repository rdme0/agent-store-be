---
name: agent-store-be-maintainer
description: Maintain AgentStore Kotlin/Spring backend code using eco-knock-be-central domain layering, JPA/Flyway, runtime execution, SSE, and payment boundaries.
---

# AgentStore BE Maintainer

Read `AI.md` first. Read the closest `eco-knock-be-central` production precedent before introducing a new pattern.
Preserve pre-existing dirty paths and never edit the reference repository.

## Package and language boundary

Use only role packages that have a real responsibility: `controller`, `service`, `repository`, `dto/request`,
`dto/response`, `dto/internal`, `model/entity`, `model/vo`, `exception`, `config`, `client`, `resolver`, `runner`,
`executor`, `orchestrator`, `event`, and `token`.

Use Kotlin for controllers, use-case services, repository interfaces, DTOs, configuration, clients, resolvers, runners,
executors, orchestrators, event components, and application-level immutable/calculation values. Use Java for JPA
entities, entity-persisted value objects/enums, and existing low-level security or infrastructure code. Every
persistence entity extends `common.model.entity.BaseEntity`.

One primary production class per file is the default. Closely related HTTP/internal DTOs may remain grouped when that
grouping is part of the contract. Do not create generic `Manager`, `Helper`, `Utils`, or `Support` layers, and do not
copy the reference repository's air-quality CQRS structure into unrelated domains.

## Dependency direction

Controllers bind HTTP, validate, document, call one use-case service, and return the contract response. They do not
access repositories, perform state transitions, parse policy, or silently correct invalid input.

An ordinary service directly uses repositories owned by its own domain. Cross-domain reads/actions go through the owning
domain's public service operation. Use an explicit orchestrator when multiple domains must coordinate one transaction or
crash boundary; it must document its lock order, transaction propagation, and recovery semantics instead of becoming a
repository grab-bag. Keep resolver/runner/client/event/token responsibilities in their role packages; do not place them
under `service` merely because Spring manages the bean.

## JPA and schema

JPA relationships are allowed when PostgreSQL FK, nullability, uniqueness, and actual cardinality justify them. Use
`FetchType.LAZY`, explicit `@JoinColumn`, and `optional`/`nullable` agreement. Use `@OneToOne` only for a unique
one-to-one constraint, and only add bidirectional/collection navigation when the use case needs it. Do not serialize
entities directly. A scalar UUID is correct for an integration reference, quote snapshot, runtime call path, or
cross-process boundary; do not mechanically convert every FK to a relation or mechanically ban `@ManyToOne`.

Flyway owns schema changes; never edit an applied migration and use the next version. Hibernate remains
`ddl-auto=validate`. Preserve table/column/index/FK/check names. Map PostgreSQL BIGINT atomic values to `BigInteger` and
expose decimal strings; map JSONB deliberately; map PostgreSQL enums with named enum support; keep timestamps as UTC
`Instant`.

## AgentStore invariants

- ACTIVE Agent versions are immutable. Dependency self/cycle, depth 5, max steps 32, and max calls 1–5 remain enforced
  with full cycle paths.
- Quote snapshots contain resolved versions, endpoints, payment terms, limits, and a five-minute expiry.
- Create durable payment intent and budget reservation before external side effects. Preserve journal, transaction hash,
  reservation, actual cost, revenue idempotency, and reconciliation state across every crash window.
- Authenticate runtime callbacks before mutating execution state. Terminalization is one atomic transition, never
  check-then-write. Unknown external outcomes remain reconciliation-required; never release or repay blindly.
- Persist execution events before publishing, replay by sequence, deduplicate replay/live delivery, and close SSE after
  terminal events. Apply the same CORS policy to raw SSE.
- x402 private keys and signed payloads stay in the official Node bridge. Spring receives typed outcomes only; bridge
  HMAC, timeout, response-size, endpoint/redirect, correlation, and reconcile semantics remain fail-closed.

## Verification

For HIGH_RISK work, write a failure matrix covering side-effect boundaries, signature/transport loss, journal/recovery,
duplicate requests, callback/terminal races, startup readiness, and SSE replay/live races, with one test mapping per
row. Use deferred clients/barriers and PostgreSQL row-lock fixtures. Run the narrowest checks first, then
`gradlew.bat classes`, `test`, `bootJar`, migration/schema validation, and OpenAPI parity. Submit a handoff with risk,
invariants, matrix, owned/pre-existing paths, contract changes, exact commands/results, assumptions, and unrun checks;
do not declare completion.
