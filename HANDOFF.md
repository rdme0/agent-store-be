# AgentStore BE 인수인계서

최종 갱신: 2026-09-06 — provider readiness 제거와 즉시 공개 경계

## 최신 로컬 실행 상태 — 2026-09-05

- 기존 `agent_store` DB와 PostgreSQL volume은 보존했다. 이번 복구에서 삭제한 것은 사용자가
  명시적으로 승인한, 이번 bootstrap 실패로 남은 정확한 DRAFT catalog Agent 행뿐이다.
  shared demo identity, Function Contract, 기존 일반 실행·공급자 결제·수익 데이터는 삭제하지
  않았다. 다만 무료 external invocation 계약으로 전환하면서 V28이 더 이상 사용하지 않는
  `external_invocation_intents`와 `external_api_sales`의 외부 입금/매출 이력은 사용자의 명시적
  승인에 따라 삭제하며 복구하지 않는다. 이 의도적인 데이터 경계는 일반 execution/payment
  데이터 보존과 구분한다.
- Spring `:8080`과 Go fixture `:8090` health를 실제 HTTP로 확인한 뒤, `POST /api/demo/access`로
  발급한 Bearer token을 프로세스 환경변수에만 보관해 `cmd/catalog-bootstrap`을 실행했다.
- `price-comparison` fixture의 잘못된 YAML key(`priceText: ₩100` + `000:`)를
  `priceText: "₩100,000"`으로 고쳐 output schema 검증을 통과시켰다.
- 복구 후 실제 `agent_store` 상태는 Function Contract 12개, Agent 13개, Version 13개이며
  13개 모두 ACTIVE다. `GET /api/agents?limit=20`도 13개를
  반환하고 `nextCursor`는 없다. Marketplace가 읽는 공개 조건을 실제 DB·HTTP 경로로 확인했다.
- V27은 provider readiness table/type과 Version `verification_input`만 제거한다. 기존 Agent,
  Version, execution, payment, revenue 데이터와 ACTIVE 상태는 보존한다.

## 저장소 역할

- 경로: 이 저장소 루트
- Kotlin/Spring 기반 AgentStore runtime이다. Spring만 PostgreSQL을 쓴다.
- 프론트엔드는 `../agent-store-fe`, demo 공급자는
  `../demo-agent`, Compose는
  `../agent-store-infra`가 소유한다.
- Spring은 Base Sepolia USDC의 x402 v2 `exact` / EIP-3009만 처리한다. private key, typed
  data, signature, raw payment header는 절대 영속화·로그 기록하지 않는다.

## 현재 작업: ACTIVE 공개와 실제 실행 결제 경계

이 작업은 HIGH_RISK다. 실패 행과 회귀 검증 매핑은
[`docs/phase-9-snowball-removal-failure-matrix.md`](./docs/phase-9-snowball-removal-failure-matrix.md)를
기준으로 하며, 공개 전 사전 인증이 아닌 실제 실행 결제 경계를 기록한다. 이전 라운드의 verifier 결과와 현재 로컬 게이트를 구분해 기록한다.

- V24는 이미 존재한 provider selection 정리 migration이다. 수정하지 않는다.
- V25는 `agent_capabilities` / `capability_id`를 `function_contracts` /
  `function_contract_id`로 rename한다. function contract API·entity·repository·service도 같은
  용어를 사용한다.
- 새 provider 선택 전략은 `lowest_price`, `latest_version`, `highest_reliability`, `fastest` 네
  개뿐이다. `balanced`, 가중치, exploration은 새 API·manifest·snapshot에서 제거한다.
- Go의 `catalog/agents.yaml`이 demo 공급자 계약의 단일 원본이다. Spring은 catalog를 seed하지 않으며,
  모든 profile의 `DevIdentityInitializer`가 shared demo developer를 보장한다. catalog bootstrap CLI가
  사용자가 발급받은 demo Bearer token으로 Function Contract → Agent manifest → publish 순서로 등록한다.
- runtime callback의 호출 시작 책임은 Root Agent 구현에 있다. Spring callback 인증, reservation,
  payment/recovery, terminal race, SSE replay 경계는 변경하지 않는다.
- `common.security`는 eco-knock-be-central의 filter/helper/SecurityContext 스타일을 따른다. callback
  invocation token과 외부 receipt token의 header parsing·HMAC/hash·만료 검증은 Security filter/helper가
  담당하고, execution/step/path/status/idempotency와 receipt resource 권한은 도메인 service가 담당한다.
- `TraceIdFilter`는 SecurityFilterChain의 인증 필터보다 먼저 실행되어 인증 실패 로그와 응답이 같은 MDC trace ID를
  사용한다. 외부 상태/SSE 경로는 canonical UUID만 인증하고, 비정규 UUID는 기존 입력 오류(400)로 거절한다.
 - Security는 stateless이며 session/basic/form login/cookie/CSRF를 사용하지 않는다. CORS는
   credential-less Bearer와 `Idempotency-Key`/receipt header를 명시하고 사용자 로그인·JWT/OAuth2·역할 권한은 아직 제공하지 않는다.
 - 외부 `/v1/invocations` POST는 6시간 demo Bearer와 `Idempotency-Key`를 받고 결제 협상 없이 202 execution을 반환한다.
   상태/SSE는 응답으로 받은 invocation receipt로만 읽는다. AgentStore가 공급자에게 지급하는 outbound x402와 recovery는 유지한다.
- Agent 목록은 `usageType=user_facing|internal_component` 필터만 받는다. `view=easy|developer`는
  UI 표시 정책이며 API 계약이 아니다.

### 이번 작업의 확정 계약

- `POST /api/agent-versions/{id}/publish`는 소유자의 DRAFT Version과 존재하는 Function Contract만 확인하고 즉시
  ACTIVE로 전환한다. 공개 전 endpoint 호출, x402 요청, 지갑/facilitator 호출, testnet 결제는 하지 않는다.
- Marketplace, 직접 실행, dependency 및 function-contract provider 후보는 ACTIVE Version만 선택한다.
- provider readiness, verification input, preflight scheduler, `/readiness`, `/verify`, backfill route는 제공하지 않는다.
- Function Contract 입력 schema 사전 검사와 output format/schema 사후 검사는 실제 실행에서 계속 강제한다.
- private key, payment header, signature, typed payload, 원본 provider body는 DB·API·로그에 남기지 않는다.

## 현재 검증 상태

- 2026-09-06 paid readiness 제거 변경에 대해 `detektMain` 0 findings, 전체 `test`, 전용
  `agent_store_integration`의 `integrationTest`가 통과했다. integration report는 67 tests,
  0 failures/errors, 1 intended skip이다.
- random-port Spring + Vite + 전용 PostgreSQL + local HTTP provider를 연결한 browser gate도 통과했다.
  이 gate는 DRAFT publish 후 ACTIVE Marketplace 노출을 실제 HTTP 경로로 확인한다.
- maintainer 자체 read-only 점검에서 현재 diff와 테스트 매핑의 blocking finding은 없었다.
  별도 verifier 재실행 결과는 아래 검증 이력과 같이 아직 없다. 테스트를 위해 띄운 Spring/Vite/provider 프로세스는 모두 종료했다.
- 별도 `agent_store_integration` 데이터베이스와 random-port Spring HTTP 서버를 사용하는
  `PostgresMarketplaceHttpE2eIntegrationTest`가 Bearer access, ownership, direct publish/Marketplace, local x402 실행 흐름을 검증한다.
  `integrationTest` 실행은 전용 PostgreSQL 환경변수만 요구하며 다른 DB로의 실행을 거부한다.
- Go catalog bootstrap은 사용자가 발급한 Bearer token을 명시적으로 받아 사용하며, token 없이는 실행하지 않는다.
- FE는 원클릭 발급 access token을 localStorage에 저장하고 만료·401·종료 시 삭제한다. `/`는 랜딩, `/marketplace`는
  catalog이며 `/agents`는 `/marketplace`로 redirect한다.

### 실행 실패·최종 결과 복구 — 2026-09-05

- `execution 854c9a59-d176-40f0-96e9-89a9387f8d8b`는 생성 뒤 정확히 30초에 Spring의 outbound x402 HTTP deadline이
  끝나 Go root request context가 취소된 사례다. 이는 `context canceled` 뒤 receipt가 없는 signed payment를
  `PAYMENT_RECONCILIATION_REQUIRED`로 보존한 안전 경로이며, 성공·재결제·수동 DB 변경으로 우회하지 않는다.
- 이전의 “facilitator pending nonce 경쟁” 원인 추정은 철회했다. x402 `exact`의 EIP-3009 nonce는 요청별 random
  authorization nonce이고, 현재 SDK의 facilitator signer가 settlement transaction을 제출한다. independent sibling
  callback은 병렬 실행한다.
- 한 노드의 처리 예산은 30초다. 그러나 callback HTTP transport와 Spring outbound x402 request는 각 target call path에서
  `남은 depth × 30초`를 계산한다. 따라서 root(depth 1)는 150초, depth 2 callback은 120초, leaf(depth 5)는 30초까지
  열려 있어 유효한 nested subtree가 30초에 취소되지 않는다. Spring `ExecutionGraphLimits`는 resolver·cost·callback
  admission·outbound x402 deadline에 사용하며, Go는 `maxDependencyDepth`가 이 cross-service 계약인 정확히 5와 다르면
  기동을 거부한다.
- 새 quote와 `POST /api/executions`를 실제 Spring `:8080` → Go fixture `:8090` → Base Sepolia x402 경로로 재실행했다.
  execution `59b32eb0-43a8-47aa-9163-55d6e543a43a`는 `COMPLETED`, 4/4 step 완료, actual cost `3400`, 모든 payment
  `SETTLED` 및 transaction hash 유효 상태다. FE `/runs/:id` 브라우저 화면에도 root Markdown 답변이 표시된다.
- FE는 `null`을 최종 출력으로 렌더링하지 않도록 별도 순수 helper와 회귀 테스트를 추가했다. 진행/실패 상태에서는 결과
  영역을 숨기고 결제·실패 안내를 유지한다.
- 첫 fresh verifier는 nested callback transport가 여전히 30초에 잘릴 수 있고 Go depth 설정이 임의값을 받을 수 있음을
  차단 결함으로 지적했다. 두 결함을 보정해 depth-2 local nested HTTP fixture, Go depth mismatch rejection, Spring
  call-path timeout regression을 추가했다. Go `go test ./...`·`go vet ./...`·`go build ./...`, Spring
  `detektMain`·`classes`·`test`·`bootJar`, 그리고 전용 `agent_store_integration` PostgreSQL과 random-port Spring
  HTTP fixture를 쓰는 `integrationTest`가 통과했다. integrationTest의 Spring test server는 graceful shutdown까지 확인했다.
  새 실제 paid execution은 추가 지출이므로 자동으로 실행하지 않았다. 보정 후 verifier 재호출은 계정 usage limit으로
  시작되지 않아, maintainer verifier checklist를 직접 재확인한 상태이며 외부 verifier PASS로 표기하지 않는다.

### 검증 이력

- 이전 verifier 라운드에서 지적된 OpenAPI·CORS·설정·integration gate·mock 격리·Kotlin 스타일·handoff
  항목을 보정했다. 현재 변경에 대한 외부 verifier 재실행 결과는 아직 없다.

### 무로그인 데모 랜딩·6시간 Bearer 인증 — 2026-09-05

- `POST /api/demo/access`는 요청 본문 없이 shared developer의 domain-separated HMAC Bearer token과 정확히 6시간 뒤
  `expiresAt`을 반환한다.
- 모든 demo developer read/mutation은 `Authorization: Bearer`만 받는다. token 없음·위조·만료는 `401` CommonResponse와 `X-Trace-Id`로 반환한다. cookie credential/CSRF와 Vite proxy는
  사용하지 않으며 CORS는 credential-less `Authorization` preflight만 허용한다.
- OpenAPI revenue query는 `@ParameterObject`로 flat `cursor`/`limit`을 발행한다. 따라서 generated frontend client가
  `/api/developer/revenue?limit=20`을 보내며 `request[limit]` 또는 수동 URL serialization workaround를 사용하지 않는다.
- `/`는 원클릭 demo CTA가 있는 랜딩이고 catalog는 `/marketplace`이다. `/agents`는 `/marketplace`로 redirect한다. 성공 token은
  browser localStorage에만 보관하며 만료·401·데모 종료 시 지우고 landing으로 돌아간다.
 - `PostgresMarketplaceHttpE2eIntegrationTest`는 real PostgreSQL + random-port Spring + local x402 fixture로
   bodyless demo access, missing bearer `401`, foreign owner `403`, DRAFT publish와 ACTIVE Marketplace 노출,
   Bearer 기반 무료 external POST와 receipt GET/SSE를 검증한다.
  dedicated `agent_store_integration` DB에서 `integrationTest`를 실행한다.

### 심사위원 중심 프론트 UX 계약 — 2026-09-05

- FE `/`는 bodyless access 발급 뒤 `/marketplace`·`/agents/:code`·`/runs/:id` 직접 접근도 목적지를 보존한
  landing guard를 거친다. 실제 catalog가 비어 있으면 fixture 성공 화면 대신 원인·복구·개발자 이동을 표시한다.
- 쉬운 사용 모드는 비용 확인, 현재 단계, reconciliation 재결제 금지와 결과/실패 복구를 우선 표시한다. 기술 ID·graph·failure code는
  개발자 모드 또는 접힘 상세에서만 보인다. dashboard Agent 관리와 수익 query는 섹션별 오류/재시도를 독립적으로 표시한다.
- BE/FE 새 회귀 범위는 `docs/phase-9-snowball-removal-failure-matrix.md`와 FE `docs/capability-marketplace-failure-matrix.md`의
  AC-FE-07~13, DA-BE-01, DA-FE-01에 매핑한다. 검증에는 FE `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`,
  `npm run test:e2e`, BE `detektMain`, `classes`, `test`, `bootJar`, `git diff --check`를 사용했다.

## 불변식

- ACTIVE Version은 immutable이며 dependency graph는 depth 5, step 32, call 1~5 한도를 지킨다.
- quote는 resolved Version, endpoint, payment terms, cost, provider 선택 근거를 고정한다. 실행 중
  provider fallback이나 재결제는 없다.
- payment unknown은 reconciliation으로 남긴다. reservation, journal, transaction hash, revenue
  projection을 추측으로 해제·생성하지 않는다.
- callback은 token을 먼저 검증하고 terminalization은 원자 전이로 한 번만 수행한다.
- SSE event는 먼저 저장하고 sequence로 replay하며 terminal event 뒤 닫는다.
- 설정의 공개 값은 YAML에 둔다. `.env`에는 secret과 Docker Compose가 직접 보간하는 값만 둔다.
- 인증 실패는 기존 CommonResponse, error code, trace header와 invocation 존재 은닉 의미를 유지하며
  raw invocation token·receipt는 SecurityContext, 로그, SSE, DB에 남기지 않는다.

## 후속 수정 메모 — 2026-09-04

- Agent 등록과 Go `catalog/agents.yaml` 실행 설정의 책임 분리를 후속 개선으로 기록했다. 구현은 보류한다.
- 세부 방향은 [`ROADMAP.md`](./ROADMAP.md#후속-개선--agent-등록과-go-실행-설정-분리), 문제 추적은
  [`COMPLEXITY_AUDIT.md`](./COMPLEXITY_AUDIT.md)의 7번 항목을 따른다. 현재 구조 설명을 완료된 개선으로 바꾸지 않는다.

## 기본 검증

```powershell
.\gradlew.bat detektMain
.\gradlew.bat classes
.\gradlew.bat test
.\gradlew.bat bootJar
git diff --check
```

관련 작업 전에는 루트 `AI.md`, 이 문서, 해당 skill을 먼저 읽는다.
