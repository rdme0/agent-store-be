# External x402 Invocation Failure Matrix (superseded)

> 이 문서는 과거 incoming x402 API 계약의 보존 기록입니다. 현재 `/v1/invocations`는
> demo Bearer로 무료 요청을 시작하고, AgentStore가 공급자에게 지급하는 outbound x402만
> 실행 중 사용합니다. 현재 실패 매핑은 `phase-9-snowball-removal-failure-matrix.md`를 기준으로 합니다.
> V28 적용 시 더 이상 사용하지 않는 incoming intent/sale 테이블과 해당 외부 입금·매출 이력은
> 명시된 무료 계약 전환 범위에 따라 제거되었으며, 아래 항목은 당시 계약의 보존 기록입니다.

## Scope and invariants

- An external caller has no account or permanent API key. An invocation receipt is a one-invocation bearer secret and only its hash is persisted.
- An intent (historical contract) froze the quote, input hash, provider cost, platform fee, total price, resource URL, and expiry before it emitted a payment challenge.
- An incoming EIP-3009 authorization must expire no later than its frozen intent. A paid intent may create its already-frozen internal execution after Quote expiry, but an unsigned or unpaid expired intent is never challenged or settled.
- The external caller pays AgentStore once with x402 v2 `exact` EIP-3009 Base Sepolia USDC. AgentStore creates an internal execution only after the incoming settlement receipt is durable.
- Incoming raw payment headers, EIP-712 payload, signature, authorization, and private keys never enter logs, exceptions, API responses, or the database.
- A settlement timeout, connection loss, missing receipt, or crash window is never treated as unpaid. It becomes reconciliation-required and must not start an execution, repay, or select another provider.
- Existing execution, provider payment, revenue projection, callback, and SSE state machines retain their current invariants.

| ID | Boundary or failure | Required result | Forbidden result | Test |
|---|---|---|---|---|
| EXT-01 | Intent has invalid schema, no provider, or quote exceeds caller maximum | Reject before payment challenge | Quote, signature, or provider payment | `ExternalInvocationServiceTest` |
| EXT-02 | Same idempotency key with same/different body | Return same intent / reject mismatch | Second frozen quote or charge | `ExternalInvocationServiceTest` |
| EXT-03 | Unsigned execute | 402 with exact fixed requirement | Execution or settlement | `ExternalInvocationControllerTest` |
| EXT-04 | Payload resource, network, asset, payTo, amount, or expiry mismatch | 402 without state transition | Facilitator settle or execution | `ExternalX402PaymentServiceTest` |
| EXT-05 | Verify rejects a signature | 402 while intent stays payment-pending | Charge or execution | `ExternalInvocationServiceTest` |
| EXT-06 | Concurrent valid retries | One `SETTLING` claim and one settlement attempt | Double settle, sale, or execution | `ExternalInvocationServiceTest` + PostgreSQL transaction advisory lock integration |
| EXT-07 | Settle success | Persist receipt/sale, then create one execution | Provider call before settlement evidence | `ExternalInvocationServiceTest` |
| EXT-08 | Settle timeout, transport loss, malformed/missing receipt, process crash window | `RECONCILIATION_REQUIRED`, no execution | Retry with a new signature, release, provider payment | `ExternalInvocationServiceTest` |
| EXT-09 | Receipt token missing or wrong | Hide intent/execution as not found | Input, output, payment, or SSE disclosure | `ExternalInvocationControllerTest` |
| EXT-10 | Root/dependency execution fails after incoming settlement | Preserve external sale and internal payment evidence | Refund assumption, duplicate incoming charge, or fallback provider | existing payment/recovery PostgreSQL suite + external service test |
| EXT-11 | SSE replay/live race after authorized invocation | Existing ordered replay and terminal behavior | Receipt bypass or stale terminal overwrite | existing SSE suite + receipt authorization service test |
