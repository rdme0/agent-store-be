---
name: agent-store-be-maintainer
description: Maintain AgentStore Kotlin/Spring code with strict readability, domain layering, Flyway, execution, SSE, and payment safety boundaries.
---

# AgentStore BE Maintainer

Read `AI.md` and `HANDOFF.md` first. Record dirty paths before editing. Read the closest
`eco-knock-be-central` precedent before introducing a pattern. Preserve pre-existing dirty paths
and never edit the reference repository.

Schema/Flyway, transaction/lock, execution/callback, payment/recovery, SSE, and OpenAPI work is
HIGH_RISK. Follow the failure-matrix and fresh-verifier workflow in `AI.md`; style cleanup never
weakens those safety requirements.

## Package and language boundary

Use only packages with a real responsibility: `controller`, `service`, `repository`, `dto/request`,
`dto/response`, `dto/internal`, `model/entity`, `model/vo`, `exception`, `config`, `client`, `codec`,
`resolver`, `runner`, `executor`, `orchestrator`, `event`, and `token`. Do not leave production
classes in a domain root package.

Use Kotlin for controllers, services, repository interfaces, DTOs, configuration, clients, codecs,
resolvers, runners, executors, orchestrators, event components, common responses, exceptions, and
application immutable/calculation values. Use Java for JPA entities and entity-persisted enums/value
objects. Every persistence entity extends `BaseEntity`.

One primary production class per file is the default. Closely related DTOs may be grouped. HTTP DTO
names end in `Request` or `Response`; internal DTO names end in `Dto`. Do not introduce generic
`Manager`, `Helper`, `Utils`, or `Support` layers.

## Layering and service design

Controllers bind HTTP, validate, document, call one use-case service, and return
`CommonResponse<T>`. They never access repositories, perform state transitions, or parse policy.
Bind related query parameters as one validated `@ModelAttribute` request DTO. Public query values
use lowercase snake_case; enum constant casing never leaks into HTTP.

An ordinary service directly uses its own domain repositories. Cross-domain reads/actions go
through the owning domain's public service. Use an orchestrator only when several domains coordinate
one transaction or crash boundary, and document lock order, propagation, and recovery semantics.

`service` contains real use cases and domain coordination. A client performs transport only; it
does not select domain policy or own settlement state. Keep cohesive decisions in a service and
extract simple one-use decisions as readable private functions. Create another component only for
a reusable algorithm, independent transport/persistence/security boundary, or separate lifecycle.
Do not create one-use `Policy`, `Evaluator`, `Verifier`, `Manager`, `Helper`, or `Utils` classes just
to avoid a private function. Do not compensate by putting unrelated responsibilities into one
oversized service.

## Kotlin readability

- Every function uses a block body and explicit `return` for returned values. Expression bodies
  (`fun value() = ...`) are forbidden in production and tests.
- Put `companion object` immediately after the class header/constructor, before instance properties
  and functions. It contains only constants, loggers, factories, and stateless converters.
- Use Kotlin named arguments for every Kotlin call or constructor with two or more arguments. Java
  APIs cannot expose Kotlin named arguments; format those calls vertically in declared order.
- Use explicit imports. Wildcard imports, fully qualified types or calls inside bodies, and repeated
  package prefixes are forbidden.
- Use blank lines between validation, transformation, external calls, and result construction.
  Preserve the reader's breathing room; do not compress independent thoughts into dense chains.
- Replace expanded literal structures such as sixteen individual zero bytes with a named constant
  and concise initializer.
- Do not create a private wrapper that merely constructs one exception or delegates one lookup. If
  a helper accepts context, use it in the decision or error-code format arguments. Unused parameters
  are forbidden.
- Injected configuration, clocks, deadlines, clients, security material, and runtime policy have no
  constructor/function defaults. Tests pass them explicitly.

## JPA, configuration, and Flyway

Prefer scalar UUID foreign keys and service boundaries over JPA navigation. Avoid `@ManyToOne`;
when unavoidable, require concrete cardinality/use-case justification, LAZY loading, explicit join
metadata, and no entity serialization. PostgreSQL BIGINT atomic values map to `BigInteger`, JSONB is
deliberate, named enums remain named, and timestamps are UTC `Instant`.

Flyway SQL owns schema changes. Never edit an applied migration; add the next version. Hibernate
remains `ddl-auto=validate`. Preserve schema/data/index/FK/check invariants.

Use Spring Boot standard datasource properties. Do not parse a deployment database URL in
application code. Flyway SQL is the only production migration mechanism: no schema fingerprint
validator, compatibility baseliner, maintenance flag, or `common/migration` production package.
PostgreSQL integration tests receive an explicit dedicated datasource and do not depend on a
production integration-profile YAML.

Never write `${ENV:default}` or `${ENV:-default}` in YAML, Compose, docs, or configuration code.
Root `.env`/`.env.example` are secret-only for the Spring process: passwords, tokens, and private
keys. Do not add a public URL, wallet address, port, timeout, TTL, fee, rate limit, payment mode, or
other policy value merely because it can vary by environment; declare those values explicitly in the
appropriate YAML configuration. Docker Compose interpolation is the sole exception: keep values it
directly requires in `.env`, even when they are not secrets. Required secret values use `${ENV}` and
required `@ConfigurationProperties` fields have no defaults. Internal protocol constants are allowed
but are passed explicitly into injected constructors.

- When three or more mutually exclusive conditions choose one value or state, prefer a `when` branch
  over an `if`/`else if` chain. Keep independent validation guards and sequential side effects as
  separate `if` statements; do not convert them mechanically.

Omit convention-identical metadata: no `@Param("query") query`, and no
`@Column(name = "created_at")` when the naming strategy already maps it. Keep annotation attributes
that express real nullability, uniqueness, column definition, or a genuinely different legacy name.

## AgentStore invariants

- ACTIVE Agent versions are immutable. Preserve dependency self/cycle, depth 5, max steps 32, max
  calls 1–5, and complete cycle paths.
- Quote snapshots include resolved versions, endpoints, payment terms, limits, and five-minute
  expiry.
- Create durable payment intent and budget reservation before external side effects. Preserve
  journal, transaction hash, reservation, actual cost, revenue idempotency, and reconciliation
  across every crash window.
- Authenticate runtime callbacks before state mutation. Terminalization is one atomic transition,
  never check-then-write. Unknown outcomes remain reconciliation-required; never repay or release
  blindly.
- Persist execution events before publishing, replay by sequence, deduplicate replay/live delivery,
  and close SSE after terminal events. Apply the same CORS policy to raw SSE.
- Native x402 supports only v2 `exact` EIP-3009 on Base Sepolia USDC. Never persist or log the hot
  wallet key, typed data, signature, or raw payment headers. Challenge matching, timeout, size,
  endpoint/redirect, duplicate correlation, and reconcile behavior remain fail-closed.

## Verification and handoff

For HIGH_RISK work, maintain a failure matrix covering side-effect boundaries, transport/signature
loss, journal/recovery, duplicates, callback/terminal races, readiness, and SSE replay/live races,
with one test mapping per row. Use deferred clients/barriers and PostgreSQL row-lock fixtures.

Run `gradlew.bat verifyProjectStyle`, then `classes`, `test`, `bootJar`, migration/schema validation,
OpenAPI parity, and `git diff --check`. Before handoff, search the complete owned diff for expression
bodies, late companions, positional multi-argument Kotlin calls, wildcard/FQ imports, environment
fallbacks, redundant metadata, DTO suffix violations, dense formatting, and unused parameters.

Submit risk, invariants, matrix, owned/pre-existing paths, contract changes, exact commands/results,
assumptions, and unrun checks. Do not declare completion before a fresh verifier reports no blocking
findings.
