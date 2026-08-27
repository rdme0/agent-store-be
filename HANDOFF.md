# AgentStore BE 인수인계서

최종 갱신: 2026-08-27

## 저장소 역할

- 경로: `C:\Users\we661\IdeaProjects\agent-store-be`
- Kotlin/Spring 기반 AgentStore runtime이다. Spring만 PostgreSQL을 쓴다.
- 프론트엔드는 `C:\Users\we661\IdeaProjects\agent-store-fe`, demo 공급자는
  `C:\Users\we661\IdeaProjects\demo-agent`, Compose는
  `C:\Users\we661\IdeaProjects\agent-store-infra`가 소유한다.
- Spring은 Base Sepolia USDC의 x402 v2 `exact` / EIP-3009만 처리한다. private key, typed
  data, signature, raw payment header는 절대 영속화·로그 기록하지 않는다.

## 현재 작업: 스노우볼 제거

이 작업은 HIGH_RISK다. 실패 행과 회귀 검증 매핑은
[`docs/phase-9-snowball-removal-failure-matrix.md`](./docs/phase-9-snowball-removal-failure-matrix.md)를
기준으로 한다.

- V24는 이미 존재한 provider selection 정리 migration이다. 수정하지 않는다.
- V25는 `agent_capabilities` / `capability_id`를 `function_contracts` /
  `function_contract_id`로 rename한다. function contract API·entity·repository·service도 같은
  용어를 사용한다.
- 새 provider 선택 전략은 `lowest_price`, `latest_version`, `highest_reliability`, `fastest` 네
  개뿐이다. `balanced`, 가중치, exploration은 새 API·manifest·snapshot에서 제거한다.
- Go의 `catalog/agents.yaml`이 demo 공급자 계약의 단일 원본이다. Spring 개발 DB initializer는
  제거됐고 catalog bootstrap CLI가 Function Contract → Agent manifest → publish 순서로 등록한다.
- runtime callback의 호출 시작 책임은 Root Agent 구현에 있다. Spring callback 인증, reservation,
  payment/recovery, terminal race, SSE replay 경계는 변경하지 않는다.
- 외부 공개 x402 API는 `/v1/invocations` resource다. 동일 `Idempotency-Key`의 unsigned POST는
  402 intent, signed POST는 202 execution을 반환한다. receipt bearer token으로 GET/SSE를 읽는다.
- Agent 목록은 `usageType=user_facing|internal_component` 필터만 받는다. `view=easy|developer`는
  UI 표시 정책이며 API 계약이 아니다.

## 현재 검증 상태

- Docker PostgreSQL에서 V1~V25 clean migration과 `PostgresSchemaIntegrationTest`를 실행했다.
  V25의 populated-schema rename 보존 regression도 이 테스트에 포함한다.
- 실행 중인 Springdoc에서 `/openapi.json`을 재생성하고 프론트 generated client를 갱신했다.
- Jackson YAML manifest parser는 alias 0개, code point 256KiB, nesting depth 32, 중복 키 거절을 적용한다.
- PostgreSQL에서 V1~V25 clean migration과 populated V25 rename 보존 regression을 통과했다.
- BE `detektMain`, `classes`, `test`, `bootJar`, FE lint/typecheck/test/build, Go test/vet/build,
  Compose config와 각 저장소 `git diff --check`를 통과한 뒤 fresh verifier를 반복한다.
- 다음 인수인계 시에는 각 저장소의 현재 커밋과 remote push 결과를 확인한다.

## 불변식

- ACTIVE Version은 immutable이며 dependency graph는 depth 5, step 32, call 1~5 한도를 지킨다.
- quote는 resolved Version, endpoint, payment terms, cost, provider 선택 근거를 고정한다. 실행 중
  provider fallback이나 재결제는 없다.
- payment unknown은 reconciliation으로 남긴다. reservation, journal, transaction hash, revenue
  projection을 추측으로 해제·생성하지 않는다.
- callback은 token을 먼저 검증하고 terminalization은 원자 전이로 한 번만 수행한다.
- SSE event는 먼저 저장하고 sequence로 replay하며 terminal event 뒤 닫는다.
- 설정의 공개 값은 YAML에 둔다. `.env`에는 secret과 Docker Compose가 직접 보간하는 값만 둔다.

## 기본 검증

```powershell
.\gradlew.bat detektMain
.\gradlew.bat classes
.\gradlew.bat test
.\gradlew.bat bootJar
git diff --check
```

관련 작업 전에는 루트 `AI.md`, 이 문서, 해당 skill을 먼저 읽는다.
