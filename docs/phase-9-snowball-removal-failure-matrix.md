# Phase 9 스노우볼 제거 failure matrix

## 범위와 소유권

- Go `catalog/agents.yaml`은 demo Agent 계약·가격·dependency의 유일한 원본이다.
- Spring은 DB writer이며, Go bootstrap이 기존 public API로 catalog를 등록한다.
- `ExecutionDto + quoteSnapshot`은 브라우저 실행 화면의 유일한 서버 상태 원본이다.
- x402 settlement journal, quote snapshot, runtime callback token, SSE replay는 제거하지 않는다.

## 고위험 경계

| ID | 불변식 또는 실패 경계 | 검증 |
| --- | --- | --- |
| SR-01 | V25는 V1~V24를 수정하지 않고 capability table/column/FK/index를 function contract 명칭으로 rename한다. | PostgreSQL schema integration test: populated function contract reference의 값·FK·index 보존과 old name 부재 |
| SR-02 | V24는 BALANCED row가 있으면 중단하며 네 enum 값과 새 dependency constraint만 남긴다. | PostgreSQL schema integration test의 enum/column/constraint introspection |
| SR-03 | quote의 네 provider strategy는 deterministic tie-break를 보장하며 metric strategy는 mature candidate 없으면 결제 전에 실패한다. | `DependencyResolverFunctionContractTest`, PostgreSQL quote regression |
| SR-04 | Go catalog와 bootstrap이 contract Schema, price, payTo, dependency manifest를 한 번만 소유하며 drift는 ACTIVE data를 덮지 않는다. | Go catalog/bootstrap unit test, first-run/re-run/drift integration |
| SR-05 | generic Go service는 callback을 실행하지 않고 root Agent만 등록된 dependency resolver를 호출한다. specialist는 호출하지 않는다. | Go agent service/root/specialist tests, callback transport regression |
| SR-06 | SSE는 cursor/replay/dedupe/connect lifecycle만 소유하며 화면은 persisted execution refresh 결과만 렌더링한다. | FE replay/reconnect/route replacement/terminal close tests |
| SR-07 | 외부 `POST /v1/invocations`는 동일 key/body에서 intent를 한 번만 만들고 402→same-request signature retry→202을 보장한다. | external controller/service tests: conflict, replay, unknown, one execution/journal/revenue |
| SR-08 | 402/202에는 payment requirement/response, receipt, invocation ID, Location을 정확히 보낸다. 구 endpoint는 OpenAPI에 없다. | MVC/OpenAPI parity tests |
| SR-09 | easy/developer는 browser display policy이며 API cursor는 q/sort/usageType만 bind한다. | backend cursor tests, FE internal-detail redirect tests |
| SR-10 | Compose는 normal service network에서 `api:8080`/`demo-agent:8090`만 허용하며 production endpoint policy는 약화하지 않는다. | compose config, endpoint/callback allowlist tests, independent health test |
| SR-11 | runtime callback invocation token은 `Authorization: Bearer ...`로 Spring→Go→Spring을 통과하고 Security filter/helper에서 인증되며 raw token은 controller/service에 전달되지 않는다. 부모 step은 token에서만 파생하고 Go callback body는 target Version·call path·input만 전송한다. | X402 agent header contract test, Go callback propagation/payload test, security filter tests, callback MockMvc tests |
| SR-12 | 외부 receipt token은 canonical UUID 상태 조회/SSE 진입 전에 인증되며 만료·위조·다른 invocation 재사용을 동일한 not-found 응답으로 거절하고 GET/HEAD 상태와 SSE를 모두 보호한다. 비정규 UUID는 receipt 검증 전에 기존 입력 오류로 남긴다. | receipt helper/filter tests, SecurityFilterChain MockMvc status/HEAD/SSE/invalid-id tests, invalid-receipt response matrix |
| SR-13 | Security 인증 실패 응답은 기존 CommonResponse·error code·trace header를 유지하고 인증 필터가 trace MDC를 먼저 설정하며 token/receipt 원문을 로그나 SecurityContext credentials에 남기지 않는다. | authentication entry-point and SecurityFilterChain MockMvc tests, trace-order/log appender and null-credentials privacy tests |
| SR-14 | CORS/CSRF/session 정책은 stateless API로 명시하고 x402 402/202 header와 raw SSE의 허용 origin을 보존한다. | SecurityFilterChain CORS preflight test, configuration review |
| AP-01 | DRAFT Version은 소유자 publish와 Function Contract 존재 확인 뒤 즉시 ACTIVE가 된다. 공개 전 x402 요청·지갑·facilitator·testnet 결제를 만들지 않는다. | `draft publish activates the version without a paid provider request and exposes it in Marketplace`, `publish rejects a foreign owner and versions that are no longer drafts` |
| AP-02 | Marketplace, 직접 실행, dependency 및 Function Contract provider 후보는 ACTIVE Version만 사용한다. | PostgreSQL Marketplace HTTP E2E와 function-contract/provider selection tests |
| AP-03 | provider 사전 인증 테이블·입력·route는 제공하지 않는다. V27은 기존 ACTIVE 상태를 보존하고 readiness 행/column/type만 제거한다. | `removed readiness routes are not published and existing active versions remain candidates` |
| DA-01 | shared demo developer는 모든 profile에서 고정 UUID로 존재하며, `POST /api/demo/access`는 별도 계정 없이 domain-separated HMAC 6시간 Bearer token을 발급한다. ID와 서명 secret은 browser bundle·로그·DB의 권한 근거가 아니다. | `PostgresMarketplaceHttpE2eIntegrationTest`의 bodyless access 발급과 `DemoAccessTokenHelperTest` six-hour/tamper boundary |
| DA-02 | Bearer token이 없거나 서명이 틀리거나 만료된 developer mutation은 `401`, principal이 다른 Agent/Version/manifest/revenue를 가리키면 `403` CommonResponse와 `X-Trace-Id`로 거절한다. 공개 Marketplace·external `/v1`·runtime callback은 demo Bearer 요구로 바뀌지 않는다. | `PostgresMarketplaceHttpE2eIntegrationTest`: `forged and expired bearer tokens are rejected before developer access`, `developer mutation requires bearer and rejects another developer agent`, `foreign version and revenue owner mutations are rejected with bearer principal`, `demo bearer is not accepted as runtime callback or external receipt authentication` |
| DA-03 | demo developer mutation은 cookie·CSRF 없이 Bearer만 사용하며 CORS는 credential 없이 `Authorization` preflight를 허용한다. runtime callback과 external x402는 별도 인증 정책을 계속 사용한다. | `openapi documents demo bearer security and x402 CORS preflight allows payment signature`, `runtime callback accepts only an invocation bearer and reaches the state machine`, `external invocation signed retry settles once and exposes receipt SSE over HTTP` |
| DA-FE-01 | 개발자 모드 진입은 환경변수 ID 없이 bodyless `/api/demo/access` → localStorage Bearer → `/api/developer/me` 순서로 identity를 확정한다. 중복 클릭·실패 재시도·만료·401·데모 종료는 현재 흐름을 오염시키지 않는다. | FE `LandingPage.test.tsx`, `generatedClient.integration.test.ts`, Playwright `public-browser.spec.ts`의 실제 local API fixture desktop/mobile 흐름 |
| DA-FE-02 | 개발자 화면은 Version의 `DRAFT`·`ACTIVE`·`DISABLED` 공개 상태만 표시한다. 공개는 사전 결제가 아니며, 실제 실행만 x402 결제 안전 경계를 따른다. | `DeveloperDashboardPage.test.tsx`, browser publish/Marketplace regression |

## 상태 전이

```text
POST /v1/invocations (key + body)
  ├─ no signature → PAYMENT_PENDING + 402 + receipt/id/location
  ├─ valid signature → SETTLING → SETTLED → EXECUTION_CREATED + 202
  └─ settlement unknown → RECONCILIATION_REQUIRED (no re-payment/fallback)
```

## verifier handoff

각 slice는 위 ID에 대응하는 테스트와 현재 diff를 함께 fresh verifier에게 제출한다. V24/V25, callback/SSE,
external payment API, OpenAPI, Compose 변경은 blocking finding이 0개가 되기 전에는 완료·커밋으로 선언하지 않는다.
Spring Security 인증 변경은 SR-11~SR-14의 filter, handler, callback race, receipt replay 회귀를 fresh verifier가 확인한 뒤에만 완료한다.

## 실제 HTTP·PostgreSQL E2E inventory — 2026-09-04

`integrationTest`는 전용 `agent_store_integration` DB와 random-port Spring 서버에서만 실행한다.
`PostgresMarketplaceHttpE2eIntegrationTest`는 Mockito/MockK 없이 JDK HTTP client, 실제
PostgreSQL fixture, 실제 security/serialization/filter chain을 사용한다. 아래 **완료** 행만 이 gate의
실행 가능한 named test가 있으며, **미완료** 행은 public operation E2E 확대가 끝나기 전에는 완료로 표시하지 않는다.

| 공개 operation | named HTTP E2E | 상태 |
| --- | --- | --- |
| `GET /health` | `health HTTP operation returns common response and trace header` | 완료 |
| `GET /api/agents` | `marketplace newest HTTP query returns active agents`, `marketplace name HTTP query returns active versions from PostgreSQL`, `marketplace HTTP rejects invalid usage type in common error envelope` | 완료 |
| `GET /api/agents/{code}` | `agent and version HTTP CRUD operations use persisted PostgreSQL fixtures` | 완료 |
| `POST/PATCH /api/agents`, `POST /api/agents/{id}/versions` | `agent and version HTTP CRUD operations use persisted PostgreSQL fixtures` | 완료 |
| `POST /api/agent-versions/{id}/disable`, `DELETE /api/agents/{id}` | `agent and version HTTP CRUD operations use persisted PostgreSQL fixtures` | 완료 (draft/has-version failure 포함) |
| `POST /api/agent-versions/{id}/publish` | `draft publish activates the version without a paid provider request and exposes it in Marketplace`, `publish rejects a foreign owner and versions that are no longer drafts` | 완료 |
| `POST /api/demo/access`, `GET /api/developer/me`, `GET /api/developer/agents`, `GET /api/developer/revenue` | `demo access issues a six hour bearer without a request body`, `demo bearer authenticates developer reads and missing bearer is rejected with common response` | bodyless access 발급, 6시간 expiry, 401 CommonResponse 포함 |
| developer mutation Bearer/ownership | `developer mutation requires bearer and rejects another developer agent` | 완료 (missing bearer, foreign Agent 403) |
| removed readiness routes/table | `removed readiness routes are not published and existing active versions remain candidates` | 완료 (404 route 및 V27 schema removal) |
| `GET/POST /api/function-contracts`, `GET /api/function-contracts/{id}`, `GET /api/function-contracts/{id}/providers` | `function contract HTTP operations persist valid schema and reject invalid schema` | 완료 |
| manifest validate/import/export/replace | `manifest validate import export and draft replace use the HTTP contract` | 완료 |
| dependency list/create/update/delete | `dependency CRUD and quote operations use real PostgreSQL ownership and active versions` | 완료 |
| quote create | `dependency CRUD and quote operations use real PostgreSQL ownership and active versions` | 완료 |
| execution create/read/SSE | `execution read and SSE replay expose a terminal event over HTTP` | 완료 |
| revenue read | `developer revenue and external intent receipt are readable over HTTP` | 완료 |
| external `/v1/invocations` POST, receipt GET/SSE | `external invocation signed retry settles once and exposes receipt SSE over HTTP` | 완료 (402 → same-key signed 202, receipt GET/SSE) |
| runtime callback | `runtime callback accepts only an invocation bearer and reaches the state machine` | 완료 |
