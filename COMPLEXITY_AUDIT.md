# AgentStore 복잡도·스노우볼 감사

기준일: 2026-08-26

## 감사 목적

이 문서는 `agent-store-be`, `agent-store-fe`, `demo-agent`, `agent-store-infra`를 하나의 제품으로 보고 다음을 찾은 결과다.

- 한 기능이 DB, API, 프론트, 테스트까지 불필요하게 번지는 스노우볼 코드
- 같은 사실을 여러 저장소나 여러 상태 모델이 중복 소유해 서로 어긋날 수 있는 구조
- 현재 트래픽과 시연 범위에 비해 너무 일찍 일반화된 구현
- 표준 기능이나 더 단순한 기술로 대체할 수 있는 자체 구현

파일이 크다는 이유만으로 제거 대상으로 판단하지 않았다. AgentStore의 핵심인 x402 결제, quote 고정, 공급자별 정산과 실패 복구를
설명하거나 보호하는 복잡도는 별도로 보존 대상으로 분류했다.

## 결론

가장 먼저 줄여야 할 것은 다음 세 가지다.

1. Spring과 Go가 각각 소유하는 중복 demo catalog를 하나의 선언 파일로 통합한다.
2. 프론트 실행 화면의 `ExecutionDto`, timeline reducer, journey model이라는 병렬 상태를 하나의 실행 projection으로 통합한다.
3. 공급자 선택은 유지하되 `balanced` 가중치와 임의 exploration을 실제 운영 데이터가 생길 때까지 제거한다.

새로운 플랫폼 기술을 더 도입할 단계는 아니다. Kafka, Temporal, 별도 payment microservice, plugin framework를 추가하면 현재 문제를
해결하지 못하고 운영 경계만 늘어난다. 우선 소유권 중복과 상태 표현 중복을 제거하는 편이 효과가 크다.

## 우선순위 요약

| 우선순위 | 항목 | 현재 문제 | 권장 방향 |
| --- | --- | --- | --- |
| P0 | demo catalog 단일 소스 | Spring과 Go의 계약이 이미 다름 | Go가 생성하는 Version manifest를 dev bootstrap이 등록 |
| P0 | 프론트 실행 상태 단일화 | snapshot, SSE, journey가 서로 다른 상태 모델을 가짐 | 단일 `ExecutionProjection`과 SSE cursor만 유지 |
| P1 | runtime callback의 역할 확정 | 동적 호출 프로토콜인데 Go가 모든 dependency를 선호출 | root Agent가 호출하도록 옮기거나 Spring orchestration으로 단순화 |
| P1 | 공급자 선택 정책 축소 | 통계·가중치·탐색이 전 계층으로 확산 | 결정적 preset만 남기고 운영 데이터 후 확장 |
| P1 | 외부 invocation API 축소 | x402 한 번 호출이 intent 생성과 execute로 분리 | 하나의 invocation resource에 402 retry를 결합 |
| P2 | UI mode의 API 누수 제거 | `easy/developer`가 서비스·cursor 계약이 됨 | 도메인 필터 `usageType`만 API에 노출 |
| P2 | manifest typed binding | 450줄 서비스가 raw YAML map을 직접 파싱 | typed document와 공통 검증 경계 사용 |
| P2 | graph UI 전략 통일 | 수제 좌표 그래프와 journey가 같은 관계를 중복 표현 | journey로 통일하거나 검증된 DAG layout 사용 |
| P2 | Compose 네트워크 정상화 | API와 Go가 network namespace를 공유 | 서비스 DNS와 dev 전용 exact allowlist 사용 |
| P3 | 작은 표준화 항목 | custom dialog, 미사용 pgvector, 공개 env | 플랫폼 표준과 일반 PostgreSQL·공개 YAML 사용 |

## 상세 감사

### P0. demo catalog가 두 저장소에 중복되어 이미 계약이 어긋났다

Spring의 `DemoCatalogInitializer.kt:30-186`과 Go의 `internal/catalog/catalog.go:95-108`이 같은 13개 Agent의 이름, 설명,
Function Contract, 가격, `payTo`, dependency를 각각 하드코딩한다. Spring에는 이를 DB 객체로 바꾸기 위한
`DemoCatalogRegistrationService.kt`도 별도로 존재한다.

단순 중복 가능성이 아니라 실제 drift가 발생했다.

- Spring specialist output schema: `DemoCatalogInitializer.kt:217-240`의 `{ "type": "object" }`
- Go specialist output schema: `internal/catalog/catalog.go:45-92`의 `summary`, `sources`, 도메인별 필수 필드와
  `additionalProperties: false`

따라서 Spring의 quote에는 느슨한 계약이 고정되고 Go runtime은 더 엄격한 결과를 만든다. AgentStore가 강조하는 계약 기반 공급자
선택을 demo catalog 자체가 증명하지 못하는 상태다.

권장 구조:

1. Go catalog를 runtime 구현과 Schema의 원본으로 둔다.
2. Go build 또는 작은 generator가 Agent별 Version manifest YAML을 만든다.
3. infra의 dev bootstrap이 기존 manifest/API를 이용해 Spring에 등록한다.
4. Spring의 `DemoCatalogSeed`, `DemoCatalogRegistrationService`, dev startup initializer를 제거한다.

Spring이 Go 내부 catalog를 HTTP로 읽으며 시작하게 만들지는 않는다. 시작 순서와 네트워크 장애가 DB bootstrap에 새로 결합되기 때문이다.
버전 관리되는 정적 manifest artifact가 더 단순하고 재현 가능하다.

### P0. 프론트 실행 상태가 세 벌이고 제거된 상태까지 보존한다

현재 실행 UI는 다음 사실을 동시에 다룬다.

- 서버 조회 결과인 `ExecutionDto`
- `features/execution/model.ts:103`의 `ExecutionTimelineState`와 `reducer.ts`
- `journeyModel.ts:239-284`가 앞의 두 상태를 다시 합쳐 만든 journey model

여기에 `ExecutionTimeline.tsx`, `ExecutionJourney.tsx`, `DependencyGraph.tsx`가 서로 다른 관점으로 같은 실행을 표시한다. 새 실행 상태를
추가하거나 제거할 때 event adapter, reducer, view model, journey model, 여러 화면과 테스트를 모두 맞춰야 한다.

실제 drift도 남아 있다.

- `features/execution/model.ts:74`, `eventAdapter.ts:54,125`, `paymentPresentation.ts:1`에 삭제된 `simulated`가 존재한다.
- generated type과 developer dashboard도 `simulated | x402`를 계속 처리한다.
- 백엔드 native-only 변경 뒤 프론트 갱신이 늦어지는 이유가 생성 artifact만이 아니라 자체 중간 모델에도 같은 계약을 복제하기 때문이다.

권장 구조:

- `ExecutionDto + quoteSnapshot`으로 초기화되는 단일 `ExecutionProjection`을 둔다.
- `useExecutionEvents`는 연결 owner, cursor, replay dedupe만 책임지고 같은 projection에 event patch를 적용한다.
- journey와 개발자 상세는 그 projection에서 selector로 계산한다.
- 별도 generic timeline state가 꼭 필요한 원본 event audit 화면이 없다면 `model/reducer/viewModel` 계층을 제거한다.
- `simulated`, 현재 백엔드에 없는 범용 terminal/payment 상태도 함께 제거한다.

persisted SSE, sequence replay와 dedupe 자체는 제거 대상이 아니다. 서버의 장애 복구 계약은 유지하고 브라우저 안의 중복 projection만
줄이는 것이 핵심이다.

### P1. runtime callback은 핵심 기능인지 middleware 편의인지 결정해야 한다

Spring은 `RuntimeCallbackService.kt`와 admission/token/terminal race 경계를 통해 Agent가 실행 중 dependency를 호출할 수 있게 한다. 이는
“Agent가 다른 Agent를 구매한다”는 제품 설명을 기술적으로 뒷받침할 수 있는 중요한 경계다.

그러나 Go의 현재 동작은 동적이지 않다.

- `internal/agent/service/agent_service.go:41-48`이 Agent 실행 전에 callback client를 호출한다.
- `internal/runtime/client/callback_client.go:40-76`이 quote에 있는 모든 dependency를 동시에 호출한다.
- 그 뒤에야 실제 Agent가 dependency 결과를 받는다.

즉, 복잡한 동적 callback 프로토콜을 운영하면서 시연 동작은 고정된 eager orchestration이다. 이 중간 상태가 가장 위험한 스노우볼이다.

권장 선택은 제품 정의에 맞춰 callback을 유지하되 호출 책임을 root Agent 구현으로 옮기는 것이다. fixture root는 필요한 dependency를
명시적으로 호출하고, OpenAI root는 허용된 dependency 중 필요한 것을 선택해 호출해야 한다. specialist에는 불필요한 runtime callback을
주지 않는다.

만약 root Agent가 호출을 결정할 계획이 없다면 반대로 Spring이 DAG를 bottom-up으로 실행하고 Go에는 결과만 넘기는 편이 훨씬 단순하다.
두 모델을 동시에 유지하지 말아야 한다. 이 결정 전에는 callback protocol에 fallback, streaming tool call 같은 기능을 더 얹지 않는다.

### P1. 공급자 선택의 핵심은 좋지만 통계 정책이 너무 빨리 확장됐다

Function Contract에 맞는 공급자를 가격이나 Version으로 고르는 기능은 AgentStore의 차별점이므로 유지해야 한다. 과한 부분은 그 위에
한 번에 추가된 운영 최적화 정책이다.

- `DependencyResolver.kt`는 924줄이다.
- `ProviderSelectionStrategy`는 `lowest_price`, `latest_version`, `highest_reliability`, `fastest`, `balanced`를 제공한다.
- `ProviderMetricService.kt:24-43,127-145`는 최소 20표본, 30일 window, Wilson lower bound, p95를 계산한다.
- dependency entity/request/response/manifest/FE editor에 exploration 비율과 reliability/price/speed 가중치가 전파된다.
- `DependencyResolver.kt:617-630,692-757`이 deterministic exploration bucket과 balanced normalization을 수행한다.

아직 실제 공급자 트래픽과 지표 분포가 없으므로 20표본, 30일, 가중치의 의미를 검증할 수 없다. UI 입력값은 정밀해 보이지만 개발자가
합리적으로 설정할 근거가 없다.

권장 축소:

- 즉시 유지: `lowest_price`, `latest_version`
- 필요하면 유지: 관측 성공률만 사용하는 `highest_reliability` preset
- 지금 제거: 임의 `explorationPercent`, 사용자 입력형 세 가중치, `balanced`
- 데이터 축적 후 검토: `fastest`, Wilson/p95 maturity 기준, platform-managed exploration

먼저 동작을 줄인 뒤 resolver를 graph resolution과 candidate ordering으로 나눈다. 924줄을 그대로 여러 클래스로 쪼개는 것은 복잡도를
이동할 뿐 줄이지 못한다.

### P1. 외부 개발자 API가 x402의 단순한 호출 경험을 가린다

현재 외부 호출은 다음 흐름이다.

1. `POST /v1/invocation-intents`로 intent를 생성하고 receipt token을 받는다.
2. `POST /v1/invocation-intents/{id}/execute`에서 402 challenge를 받는다.
3. 같은 execute endpoint에 payment signature를 보내 실행한다.
4. 별도 GET/SSE로 상태를 확인한다.

근거는 `ExternalInvocationController.kt:47-104`와 429줄의 `ExternalInvocationService.kt`다. 내부 durable intent와 receipt hash,
idempotency lock은 결제 안전에 필요하지만 외부 resource를 두 단계 명령처럼 노출할 필요는 없다.

권장 공개 계약:

- `POST /v1/invocations` 한 경로가 body와 `Idempotency-Key`를 기준으로 intent를 영속화한다.
- 결제가 없으면 같은 응답에서 402와 invocation receipt를 반환한다.
- 같은 body/key/receipt와 `PAYMENT-SIGNATURE`로 재요청하면 settlement 후 202와 status URL을 반환한다.
- `GET /v1/invocations/{id}`와 SSE는 유지한다.

내부 상태 머신과 journal은 그대로 두면서 사용자가 이해해야 하는 create/execute 구분만 없앨 수 있다. 이것이 “외부 개발자 → AgentStore
라우팅 → 공급자”라는 사용 사례와 x402의 same-request retry 형태에 더 가깝다.

### P2. 쉬운 사용/개발자 모드가 API 도메인으로 누수됐다

브라우저 표시 선택인 `easy | developer`가 `AgentView.kt`, `AgentService.kt`, `AgentListCursorCodec.kt`와 API query에 들어가 있다.
easy detail에서 internal Agent를 not-found 처리하며 cursor signature도 UI mode에 묶인다. 프론트는 generated client가 이 query를
표현하지 못해 `entities/agent/api.ts:73-79`에서 `as never`로 우회한다.

권장 구조:

- API 목록에는 도메인 필터인 nullable `usageType=user_facing|internal_component`만 둔다.
- easy UI는 `usageType=user_facing`을 요청하고 developer UI는 필터를 생략한다.
- 상세 조회는 UI mode와 무관하게 같은 resource를 반환한다. 실제 비공개 제어가 필요해지면 인증/권한으로 처리한다.
- cursor는 `usageType` 같은 실제 검색 조건에만 binding한다.

이렇게 해야 모바일 앱이나 외부 client가 `developer mode`라는 프론트 개념을 알아야 하는 상황을 막을 수 있다.

### P2. manifest는 필요하지만 raw YAML parser는 유지보수 비용이 크다

`AgentManifestService.kt`는 453줄이며 `Map<String, Any?>`를 대상으로 `requireAllowedKeys`, `requireMap`, `requireString`,
`requireInt`, `requireBoolean` 등을 직접 구현한다(`:210-406`). import validation과 실제 request/service validation도 별도로 존재한다.

manifest 자체는 제거하면 안 된다. 오히려 중복 demo catalog를 없애는 단일 계약으로 적극 사용해야 한다. 구현만 다음처럼 바꾼다.

- `AgentManifestDocumentDto`와 중첩 typed DTO를 만든다.
- 프로젝트의 Jackson 사용 방식에 맞춰 `jackson-dataformat-yaml`로 typed binding한다.
- 알 수 없는 필드 거절, Bean Validation, Function Contract Schema 검증을 기존 service 경계와 공유한다.
- canonical serialization과 SHA-256 digest만 manifest service의 고유 책임으로 남긴다.

새 범용 parser framework를 만들지 말고 이미 쓰는 Jackson/validation 모델을 재사용한다.

### P2. dependency graph를 수제 배치하면서 journey와 중복 유지한다

`DependencyGraph.tsx:55-85`는 배열 순서를 고정 3열 좌표로 바꾸고, `:170-233`은 SVG 직선과 HTML node를 직접 겹쳐 그린다.
DAG depth나 edge 교차를 고려하는 layout이 아니어서 graph가 커질수록 시각 품질을 직접 고쳐야 한다. 동시에 쉬운 실행 화면에는 이미 세로형
`ExecutionJourney`가 있다.

둘 중 하나를 선택한다.

- 관계 이해가 목적이면 quote와 실행 모두 세로 journey/tree로 통일하고 custom SVG를 삭제한다.
- 개발자에게 임의 DAG 탐색이 핵심이면 `@xyflow/react`와 Dagre/ELK 같은 검증된 layout을 사용한다.

현재처럼 custom graph와 journey를 둘 다 독자적으로 발전시키는 방식은 피한다. 모바일에서는 계속 journey만 사용한다.

### P2. Compose network namespace 공유가 서비스 독립성을 해친다

infra `compose.yaml:36-38,49-65`에서 demo-agent가 `network_mode: service:api`를 사용하고 API가 Go의 8090 포트까지 대신 publish한다.
이는 DB에 고정된 `127.0.0.1:8090` endpoint와 callback의 `127.0.0.1:8080`을 컨테이너에서도 그대로 쓰기 위한 우회다. 한 서비스의
네트워크 변경과 재시작이 다른 서비스에 결합된다.

권장 구조:

- 일반 Compose network와 `http://api:8080`, `http://demo-agent:8090` 서비스 DNS를 사용한다.
- dev catalog manifest가 container endpoint를 명시적으로 선택하도록 한다.
- endpoint SSRF 정책은 production에서 그대로 유지하고, dev에서만 exact service hostname allowlist를 둔다.
- API는 8080, demo-agent는 8090을 각자 publish하고 독립적으로 재시작한다.

보안 정책 전체를 느슨하게 만들지 않고 개발 환경의 정확한 host만 허용해야 한다.

### P3. 표준 기능으로 바로 줄일 수 있는 항목

#### custom dialog

`AgentDetailPage.tsx:228-272`가 Escape, Tab 순환, focusable 검색과 focus 복구를 직접 구현한다. 요청 owner lock은 필요하지만 modal의
접근성 동작은 native `<dialog>.showModal()` 또는 프로젝트에서 하나로 정한 접근성 dialog component에 맡길 수 있다.

#### 미사용 pgvector image

infra `compose.yaml:5`는 `pgvector/pgvector:pg17`을 사용하지만 백엔드에 vector column, embedding, pgvector query가 없다. 현재는
`postgres:17`로 충분하다. 실제 검색 migration이 생길 때 pgvector를 다시 도입한다.

#### 공개 설정의 env 집중

Go `.env.example:1-5`에는 host, port, 실행 mode와 facilitator URL이 함께 있다. secret은 `OPEN_AI_KEY`뿐이다. Compose가 직접
override하는 host/port는 예외로 둘 수 있지만, 로컬 기본 정책과 공개 URL은 작은 checked-in YAML 또는 명시적 실행 flag로 옮기는 편이
현재 프로젝트의 “secret-only env” 원칙과 맞다. 이를 위해 Viper 같은 설정 framework를 추가할 필요는 없다.

## 줄이면 안 되는 복잡도

다음은 코드량이 많아도 제품 차별점 또는 금전 안전 경계이므로 단순 CRUD처럼 축소하면 안 된다.

- Base Sepolia USDC x402 v2 exact/EIP-3009 challenge 일치 검증과 native signing boundary
- payment intent, journal, reservation, transaction hash, revenue projection의 crash-window 복구
- 결제 결과가 불명확할 때 재결제하지 않는 reconciliation 정책
- quote 발급 시 Agent, Version, endpoint, 계약, 가격과 최대 비용을 고정하는 snapshot
- callback을 유지하기로 했다면 token 인증, admission lock과 terminal race 처리
- persisted SSE sequence, replay/live dedupe와 terminal close
- 결제 전 input Schema와 결제 후 output Schema 검증
- DNS pinning, redirect 금지, body/deadline 제한과 production endpoint 정책

이 경계들은 클래스 수를 줄이기 위해 합치기보다 상태 전이와 lock order가 읽히도록 유지해야 한다.

## 권장 실행 순서

### 1단계 — drift를 먼저 멈춘다

1. native-only OpenAPI를 재생성하고 프론트 generated type과 자체 `simulated` 분기를 제거한다.
2. Go catalog에서 manifest를 생성하고 Spring demo initializer를 제거한다.
3. catalog/schema parity test를 추가해 같은 drift가 재발하지 않게 한다.

### 2단계 — 동작을 줄인 뒤 코드를 줄인다

1. 공급자 정책에서 `balanced`, 사용자 가중치, exploration을 제거한다.
2. 축소된 정책을 기준으로 resolver와 dependency form을 정리한다.
3. manifest를 typed binding으로 바꾼다.

### 3단계 — 실행 모델의 주인을 하나로 만든다

1. runtime callback의 호출 주체를 root Agent 또는 Spring 중 하나로 확정한다.
2. 프론트 `ExecutionProjection`을 정의하고 timeline/journey의 병렬 상태를 통합한다.
3. custom graph와 journey 중 주 표현을 하나로 정한다.

### 4단계 — 외부 경계와 개발 환경을 단순화한다

1. 외부 invocation API를 single-resource 402 retry로 합친다.
2. UI `view` 대신 `usageType` filter를 사용한다.
3. Compose를 일반 service network로 바꾼다.
4. native dialog, 일반 PostgreSQL image, 공개 설정 위치를 정리한다.

각 단계는 기능별로 별도 변경한다. 특히 payment/recovery, runtime callback, SSE, OpenAPI, Flyway 변경은 기존 `HIGH_RISK` failure
matrix와 fresh verifier 절차를 그대로 적용한다. 아직 배포 전이라도 현재 적용된 Flyway migration을 조용히 수정하지 않는다. 전체 개발 DB
reset과 baseline 재구성을 별도로 승인한 경우에만 migration squash를 검토한다.

## 새 기능 제안 전 판단 기준

다음 질문 중 하나라도 답하지 못하면 기능을 바로 추가하지 않는다.

1. `ROADMAP.md`의 어느 Phase와 완료 기준을 직접 진전시키는가?
2. 시연에서 사용자가 이 기능의 효과를 직접 볼 수 있는가?
3. 같은 사실을 다른 저장소·DTO·상태 모델이 이미 소유하고 있지 않은가?
4. 운영 데이터가 없는데도 임계값, 가중치, fallback 정책을 먼저 만들고 있지 않은가?
5. 표준 브라우저/API/library 기능으로 대체할 수 있는 자체 구현은 아닌가?
6. 기능을 제거해도 x402 거래, Agent 선택, 정산 증명이라는 핵심 설명이 그대로 성립하지 않는가?

목표는 단순히 코드 줄 수를 줄이는 것이 아니다. AgentStore의 핵심 거래 흐름은 더 선명하게 보이고, 그 흐름과 무관한 선택지와 중복
표현만 줄이는 것이다.
