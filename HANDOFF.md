# AgentStore BE 인수인계서

최종 갱신: 2026-09-05 — 원클릭 365일 Bearer 전환 및 실제 HTTP·PostgreSQL E2E 완료

## 최신 로컬 실행 상태 — 2026-09-04

- 기존 `agent_store` DB와 PostgreSQL volume은 보존했다. 이번 작업에서는 전용
  `agent_store_integration` 데이터베이스만 생성해 opt-in 통합 테스트에 사용한다.
- Spring dev 기동으로 V1~V26 migration 상태를 확인했다. V26은 제3자 공급자 readiness 테이블과
  Version별 `verification_input`을 추가하며 기존 Agent·실행·결제 데이터를 삭제하지 않는다.
- 이 인수인계 시점에 Spring·Go 개발 서버는 종료되어 있으며 PostgreSQL Compose 상태는 별도로 확인한다.

## 저장소 역할

- 경로: 이 저장소 루트
- Kotlin/Spring 기반 AgentStore runtime이다. Spring만 PostgreSQL을 쓴다.
- 프론트엔드는 `../agent-store-fe`, demo 공급자는
  `../demo-agent`, Compose는
  `../agent-store-infra`가 소유한다.
- Spring은 Base Sepolia USDC의 x402 v2 `exact` / EIP-3009만 처리한다. private key, typed
  data, signature, raw payment header는 절대 영속화·로그 기록하지 않는다.

## 현재 작업: 제3자 x402 공급자 온보딩·Readiness 검증

이 작업은 HIGH_RISK다. 실패 행과 회귀 검증 매핑은
[`docs/phase-9-snowball-removal-failure-matrix.md`](./docs/phase-9-snowball-removal-failure-matrix.md)를
기준으로 하며, provider readiness 행(PR-01~PR-07)을 추가했다. 최신 fresh verifier는 PASS를 보고했다.

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
  credential-less Bearer와 외부 x402 header를 명시하고 사용자 로그인·JWT/OAuth2·역할 권한은 아직 제공하지 않는다.
- 외부 공개 x402 API는 `/v1/invocations` resource다. 동일 `Idempotency-Key`의 unsigned POST는
  402 intent, signed POST는 202 execution을 반환한다. receipt bearer token으로 GET/SSE를 읽는다.
- Agent 목록은 `usageType=user_facing|internal_component` 필터만 받는다. `view=easy|developer`는
  UI 표시 정책이며 API 계약이 아니다.

### 이번 작업의 확정 계약

- `POST /api/agent-versions/{id}/publish`는 단순 상태 변경이 아니라 paid certification이다.
  Function Contract가 있는 DRAFT Version과 schema-valid `verificationInput`만 대상이다.
- unsigned probe는 등록 endpoint에 요청해 `402 + PAYMENT-REQUIRED`의 Base Sepolia USDC,
  x402 v2 `exact` / EIP-3009 terms가 Version의 endpoint·가격·asset·network·`payTo`와 정확히
  일치하는지 확인한다. 이후 플랫폼 testnet wallet이 한 번만 결제해 200, receipt transaction hash,
  response format 및 Function Contract output schema를 검증해야 ACTIVE + VERIFIED가 된다.
- `agent_version_readiness`의 상태는 `UNVERIFIED`, `VERIFYING`, `VERIFIED`, `UNAVAILABLE`, `UNKNOWN`이다.
  Marketplace, 직접 실행, dependency 및 function-contract provider 후보는 반드시 `ACTIVE + VERIFIED`여야 한다.
- 15분 scheduler는 VERIFIED Version에 unsigned preflight만 하고, 실패하면 즉시 `UNAVAILABLE`로 제외한다.
  preflight는 payment journal·settlement를 만들지 않는다. 기존 Quote snapshot은 readiness 변경으로 수정하지 않는다.
- private key, payment header, signature, typed payload, 원본 provider body는 DB·API·로그에 남기지 않는다.

## 현재 검증 상태

- 현재 변경에 대해 `detektMain` 0 findings, `classes`, 전체 `test`, `bootJar`, `git diff --check`가 통과했으며
  fresh verifier도 PASS를 보고했다.
- 별도 `agent_store_integration` 데이터베이스와 random-port Spring HTTP 서버를 사용하는
  `PostgresMarketplaceHttpE2eIntegrationTest`가 Bearer access, ownership, readiness, local x402 흐름을 검증한다.
  `integrationTest` 실행은 전용 PostgreSQL 환경변수만 요구하며 다른 DB로의 실행을 거부한다.
- Go catalog bootstrap은 사용자가 발급한 Bearer token을 명시적으로 받아 사용하며, token 없이는 실행하지 않는다.
- FE는 원클릭 발급 access token을 localStorage에 저장하고 만료·401·종료 시 삭제한다. `/`는 랜딩, `/marketplace`는
  catalog이며 `/agents`는 `/marketplace`로 redirect한다.

### 검증 이력

- 이전 verifier 라운드에서 지적된 readiness·OpenAPI·CORS·설정·integration gate·mock 격리·Kotlin 스타일·handoff
  항목을 보정했다. 최신 fresh verifier는 수정된 BE/FE/Go 트리와 회귀 테스트를 다시 읽고 PASS를 보고했다.

### 원클릭 무로그인 데모 랜딩·365일 Bearer 인증 — 2026-09-05

- `POST /api/demo/access`는 본문 없이 shared developer의 domain-separated HMAC Bearer token과 정확히 365일 뒤 `expiresAt`을 반환한다.
- 모든 demo developer read/mutation은 `Authorization: Bearer`만 받는다. token 없음·위조·만료는 `401` CommonResponse와 `X-Trace-Id`로 반환한다. cookie credential/CSRF와 Vite proxy는
  사용하지 않으며 CORS는 credential-less `Authorization` preflight만 허용한다.
- OpenAPI revenue query는 `@ParameterObject`로 flat `cursor`/`limit`을 발행한다. 따라서 generated frontend client가
  `/api/developer/revenue?limit=20`을 보내며 `request[limit]` 또는 수동 URL serialization workaround를 사용하지 않는다.
- `/`는 원클릭 demo CTA가 있는 랜딩이고 catalog는 `/marketplace`이다. `/agents`는 `/marketplace`로 redirect한다. 성공 token은
  browser localStorage에만 보관하며 만료·401·데모 종료 시 지우고 landing으로 돌아간다.
- `PostgresMarketplaceHttpE2eIntegrationTest`는 real PostgreSQL + random-port Spring + local x402 fixture로
  본문 없는 demo success, missing bearer `401`, foreign owner `403`, legacy backfill and paid verify를 검증한다.
  dedicated `agent_store_integration` DB에서 `integrationTest`를 실행한다.

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
