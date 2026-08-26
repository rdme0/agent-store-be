# Phase 8 — native x402-only payment failure matrix

## Invariants

- Every production invocation uses the native Base Sepolia USDC x402 v2 `exact` EIP-3009 path.
- A durable settlement journal and a valid Base Sepolia transaction hash are the only proof of spend.
- Unknown post-signing outcomes retain their reservation and are never repaid automatically.
- Removing payment mode must not weaken execution, settlement, revenue, recovery, callback, or SSE atomicity.
- The schema migration never deletes or rewrites existing simulated records implicitly.

| ID | Boundary / fault | Expected behavior | Test coverage |
| --- | --- | --- | --- |
| PAY-01 | Spring starts without `X402_PRIVATE_KEY` | Bean creation fails before serving requests | x402 configuration test |
| PAY-02 | Agent returns mismatched or unsupported 402 requirement | Fail before signature and release the reservation | x402 payment service test |
| PAY-03 | Signed invocation times out, disconnects, or lacks a valid receipt | Attempt becomes `RECONCILIATION_REQUIRED`; no retry or revenue | x402 payment/recovery tests |
| PAY-04 | Valid settlement receipt followed by Agent 4xx/5xx | Journal and revenue persist once; step terminalizes as paid invocation failure | execution payment tests |
| PAY-05 | Process restarts with a `REQUIRED` attempt under active or terminal execution | Attempt is reconciliation-required and reservation remains held | execution recovery integration test |
| PAY-06 | Reconciliation reports settled after restart | Existing attempt is projected once without a second payment | settlement recovery integration test |
| PAY-07 | Local projection fails after settlement | Journal/hash remain authoritative; recovery marker and terminal state persist | settlement recovery integration test |
| PAY-08 | Concurrent callback, terminalization, and settlement | One locked terminal/projection outcome; no duplicate revenue | callback terminal race integration test |
| PAY-09 | V23 encounters historical simulated rows | Migration aborts without deleting or rewriting data | PostgreSQL schema integration test |
| PAY-10 | V23 encounters only x402 rows | Mode columns/type are removed; attempts, journals, and revenue remain readable | PostgreSQL schema integration test |
| PAY-10A | V23 encounters revenue without a transaction hash | Migration aborts rather than presenting unproven revenue as native settlement | PostgreSQL schema integration test |
| PAY-11 | SSE/API consumer receives payment data | No payment-mode field is emitted; status, amount, and transaction hash remain correct | execution response/OpenAPI tests |
