# Spring Phase 5 — native x402 EIP-3009 failure matrix

This slice is `HIGH_RISK`. Spring commits a durable payment attempt and budget reservation before
invoking an Agent.
The same Spring process holds a low-balance hot-wallet key and implements x402 v2 `exact`
for Base Sepolia USDC with EIP-3009. It never persists or logs the key, typed-data payload,
signature, or raw x402
headers.

## Invariants and trust boundaries

- Only `eip155:84532`, Base Sepolia USDC `0x036CbD53842c5426634e7929541eC2318f3dCF7e`, `exact`, and
  `assetTransferMethod=eip3009` (or an omitted method) are supported.
- The requested amount must stay within the frozen maximum price, and the `402` challenge must
  exactly match the
  requested amount, asset, payee, endpoint resource URL, and network before any signature is
  created.
- `X402AgentClient` resolves and pins DNS once for both unpaid and paid requests. Production permits
  HTTPS and exclusively public
  addresses; redirects, credentials, fragments, mixed/private answers, and responses over 1 MiB fail
  closed.
- A signature is an irreversible external-side-effect boundary. Any transport or receipt ambiguity
  after signing keeps
  the durable reservation and becomes `RECONCILIATION_REQUIRED`; it never triggers an automatic
  repayment.
- Only a successful `PAYMENT-RESPONSE` for Base Sepolia with a 32-byte EVM transaction hash proves
  settlement. Local
  journal, cost, revenue, and event projection remain idempotent and happen after that proof.
- In-memory correlation is keyed by `(paymentAttemptId, idempotencyKey)`. A JVM restart erases it,
  so unresolved
  attempts remain `UNKNOWN` and reserved.

## State transitions

```text
durable REQUIRED/RESERVED
  -> unpaid Agent request
  -> validated 402 challenge
  -> EIP-3009 signature created
  -> paid Agent request
     -> valid successful receipt -> SETTLED -> durable journal -> local projection
     -> valid receipt + Agent error -> SETTLED/PAID_INVOCATION_FAILED -> durable journal -> failure projection
     -> ambiguous/missing receipt -> RECONCILIATION_REQUIRED (reservation retained)
```

## Failure and test matrix

| Stage               | Failure or race                                                                                 | Required state and retry behavior                                                                                                | Test mapping                                                                                                                                                   |
|---------------------|-------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Startup             | absent, malformed, zero, or out-of-range private key                   | Fail bean creation before serving requests; never echo the key                                                                   | `X402PaymentConfigurationTest`; `X402Eip3009SignerTest.zero and out of range private keys are rejected`                                                  |
| Before request      | invalid amount/max, network, asset, payee, endpoint, body over 1 MiB                            | Definite failure before network or signing; durable reservation follows the existing failure path                                | `X402PaymentServiceTest.oversized request fails before the unpaid Agent request`; existing endpoint policy tests                                               |
| Endpoint connect    | SSRF, DNS failure/rebind, redirect, private/link-local/multicast/reserved address               | `X402AgentClient` uses `AgentEndpointPolicy` and the pinned client for both attempts; reject redirect and never follow it        | existing endpoint/pinned DNS tests; `X402PaymentServiceTest.redirect is not followed`                                                                          |
| Unpaid request      | non-402, missing/malformed/oversized challenge                                                  | Definite failure because no signature exists; do not retry                                                                       | `X402PaymentServiceTest.challenge must exactly match quote before signing`; `oversized unpaid response is rejected before signing`                             |
| Header codec        | non-standard base64 or non-object JSON                                                          | Reject before signing; emit standard padded base64 JSON for v2 headers                                                           | `X402HeaderCodecTest` fixed `PAYMENT-REQUIRED` and `PAYMENT-RESPONSE` wire vectors                                                                             |
| Challenge selection | v1, wrong resource/amount/network/asset/payee, missing EIP-712 name/version, Permit2/ERC-7710   | Definite `PRICE_MISMATCH` or unsupported-method failure before signing                                                           | `X402PaymentServiceTest.challenge must exactly match quote`; `unsupported transfer method is not signed`                                                       |
| Duplicate attempt   | concurrent same attempt/key                                                                     | One owner performs the flow; waiters observe the same typed result/error; no second signature/request                            | `X402PaymentCorrelationRegistryTest.concurrent duplicate coalesces`                                                                                            |
| Signature           | invalid typed-data construction or signing failure                                              | Definite failure; no paid request                                                                                                | `X402Eip3009SignerTest` fixed Viem-compatible vector and private-key boundary tests                                                                            |
| Paid request        | hard 30-second deadline, connection loss, response over 1 MiB, malformed/missing receipt        | `RECONCILIATION_REQUIRED`; retain reservation and never retry automatically, including a response that continuously drips chunks | `X402PaymentServiceTest.post signature ambiguity remains unknown`; `slow paid response cannot extend the absolute invocation deadline`                         |
| Paid response       | successful receipt, Agent 2xx                                                                   | Record transaction/payment identifier once, then return output                                                                   | `X402PaymentServiceTest.successful receipt returns settled output`                                                                                             |
| Paid response       | successful receipt, Agent 4xx/5xx                                                               | Preserve settlement evidence and return `PAID_INVOCATION_FAILED` semantics for terminalization                                   | `X402PaymentServiceTest.settled agent failure preserves receipt`                                                                                               |
| Paid response       | `success=false`, wrong network, or malformed transaction hash                                   | Treat as post-signature unknown unless the protocol response proves no settlement; current policy remains conservative           | `X402PaymentServiceTest.non successful receipt remains unknown`                                                                                                |
| Reconciliation      | exact in-memory settled correlation                                                             | Return `SETTLED` only when attempt/key and stored evidence agree; never invoke or sign                                           | `X402PaymentCorrelationRegistryTest.reconcile only returns recorded settlement`                                                                                |
| JVM restart         | process dies after signing/sending but before the catch marks the still-`REQUIRED` x402 attempt | Startup recovery promotes every `REQUIRED` attempt to `RECONCILIATION_REQUIRED`; retain reservation and never infer non-payment         | `PostgresSettlementRecoveryIntegrationTest.startup recovery promotes unmarked required x402 payment and retains reservation` |
| JVM restart         | correlation lost after an attempt is already marked unknown                                     | Return `UNKNOWN`; retain reservation indefinitely for manual/on-chain resolution                                                 | `PaymentSettlementRecoveryServiceTest`; PostgreSQL startup recovery integration test                                                                           |
| Projection          | crash after receipt/journal or concurrent recovery/terminalization                              | Journal and attempt marker make actual cost, revenue, and `PAYMENT_SETTLED` exactly once; preserve terminal failure              | existing `PostgresSettlementRecoveryIntegrationTest` race rows                                                                                                 |
| Startup recovery    | unknown payment or recovery error                                                               | Readiness stays closed until recovery finishes; unknown reservation is not released                                              | existing `PaymentRecoveryStartupServiceTest` and readiness integration rows                                                                                    |

An actual funded Base Sepolia smoke test remains opt-in. It must use a dedicated low-balance wallet
and must never print
the private key or payment signature.
