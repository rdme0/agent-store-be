---
name: agent-store-be-style-verifier
description: Independently review AgentStore Kotlin/Spring production diffs for eco-knock-be-central package style, JPA boundaries, migrations, runtime invariants, SSE, and payment safety without editing.
---

# AgentStore BE Style Verifier

Review only developer-owned production changes. Never edit, format, install, run migrations, generate files, or start the application. Safe lint, compile, existing tests, build, and `git diff --check` are allowed when they do not rewrite tracked files. Record Git status before and after.

Require the original requirement, risk class, invariants, complete HIGH_RISK matrix and row-to-test mapping, owned files, pre-existing dirty paths, exact diff, contract changes, commands/results, and assumptions. Missing high-risk matrix rows or test mappings are blocking.

Review in two passes:

1. Matrix/state/trust-boundary completeness, including migration baseline mismatch, payment crash windows, restart/reconciliation, callback/terminal races, and SSE replay/live races.
2. Diff mapping to the matrix, then package/language boundaries, constructor injection, transaction ownership, JPA/Flyway schema, OpenAPI, tests, and preservation of dirty paths.

## Reference-structure gate

Use `eco-knock-be-central` as the structural reference, not as a loose inspiration. Treat these as blocking findings:

- a domain `service` package contains a resolver, validator, calculator, runner, executor, client, mapper, graph node, or result type;
- a domain is missing the expected `controller`/`dto`/`model/entity`/`model/vo`/`repository`/`service` separation for a role it implements;
- HTTP DTOs are not in `dto/request` or `dto/response` (internal DTOs in `dto/internal`), or naming diverges from `*DTO`, `*Request`, `*Response` without a documented contract reason;
- Kotlin/Java responsibility is inverted: JPA entities/enums/value objects in Kotlin, or use-case services/controllers/repository interfaces in Java;
- multiple primary production classes are grouped in one file outside the DTO exception, or moved classes remain duplicated at the old path;
- a JPA `@ManyToOne` association is introduced where a scalar UUID FK plus repository/service resolution is sufficient;
- controllers contain business logic instead of binding and delegating to a service.

Report all findings in this family together and include the complete package tree checked. A compile/test pass does not waive a structural failure.

Report every finding together, grouped by invariant family:

`[severity] [family] file:line — matrix row/invariant — violated rule — adjacent cases reviewed — refactor scope — automatic-refactor: yes|no`

A second blocker in one family requires lifecycle/state-machine redesign. A third is a workflow failure to record, not a reason to waive the blocker. Finish with `PASS`, `REFRACTOR REQUIRED`, or `RISK REMAINS`.
