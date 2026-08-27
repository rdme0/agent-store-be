# AgentStore 복잡도·스노우볼 감사

기준일: 2026-08-27

## 감사 목적

이 문서는 현재 코드에 남아 있는 복잡도만 기록한다. 해결된 감사 항목과 과거 실행 순서는 제거하며, 완료 이력은 Git history와
`HANDOFF.md`에서 확인한다.

- 문자열 기반 계약이나 수동 환경 준비처럼 표준 기능으로 실제 제거할 수 있는 코드
- 여러 서비스가 나눠 가진 parsing·validation 책임
- 향후 요구가 생겼을 때만 도입해야 하는 기술과 현재 도입하면 안 되는 기술
- 코드량이 많아도 결제·복구·quote·SSE 안전을 위해 보존해야 하는 경계

파일이 크다는 이유만으로 제거 대상으로 판단하지 않는다. AgentStore의 핵심인 x402 결제, quote 고정, 공급자별 정산과 실패 복구를
설명하거나 보호하는 복잡도는 보존 대상으로 분류한다.

## 남은 감사 항목

| 판단 | 대상 | 현재 복잡도 | 권장 해법 |
| --- | --- | --- | --- |
| 우선 적용 | quote snapshot 소비 | typed DTO가 있는데 실행 경로가 다시 `JsonNode.path(...)`로 읽음 | 저장 시 JSONB를 유지하고 읽기 경계를 `QuoteSnapshotDto`로 통일 |
| 우선 적용 | PostgreSQL integration test | 전용 DB와 환경변수를 개발자가 미리 준비해야 함 | Testcontainers `PostgreSQLContainer`와 Spring Boot `@ServiceConnection` 사용 |
| 적용 가치 있음 | Version constraint | parser·정규화·비교가 748줄 resolver에 함께 있음 | `VersionConstraint` immutable value object로 파싱 책임 분리 |
| 조건부 적용 | runtime callback 인증 | controller가 header를 받고 service가 Bearer parsing과 HMAC 검증까지 수행 | Spring Security filter chain에서 인증하고 typed principal만 service에 전달 |
| 제한적 적용 | 단순 외부 HTTP API | JDK `HttpClient` request·JSON binding boilerplate | 안전 요구가 단순한 client만 Spring HTTP Service Client 또는 `RestClient` 사용 |
| 요구 발생 후 | execution dispatch | 수동 `afterCommit`과 `@Async` 사이에 crash gap이 있음 | 실행 재개가 필요해질 때만 DB-backed job/outbox 또는 durable event publication 도입 |

### 1. quote snapshot은 typed DTO를 실제 읽기 모델로 사용한다

`QuoteSnapshotDto.kt`에는 `ResolvedVersionSnapshotDto`, `DependencySnapshotDto`와 legacy `agentSlug` alias까지 정의되어 있다.
하지만 `QuoteService.snapshot()`은 정규화한 `JsonNode`를 반환하고, `ExecutionService`, `ExecutionRunner`,
`RuntimeCallbackService`, `ExternalInvocationService`가 `version`, `endpoint`, `priceAtomic`, `functionContract`,
`dependencies`를 문자열 key로 다시 탐색한다.

이 구조에서는 필드명이 바뀌어도 컴파일이 성공하고, 누락된 숫자가 `0`으로 처리되는 등 실패가 실제 실행 시점까지 늦어진다.

1. JSONB 저장과 과거 snapshot 보존은 그대로 유지한다.
2. `QuoteService` 한 곳에서 legacy alias를 포함해 `QuoteSnapshotDto`로 역직렬화한다.
3. 실행·callback·외부 invocation은 typed snapshot만 받는다.
4. 공개 응답에 JSON tree가 필요하면 같은 DTO를 Jackson으로 직렬화한다.

새 dependency 없이 이미 쓰는 Jackson과 DTO로 여러 서비스의 방어적 `.path()` 분기와 문자열 key를 제거할 수 있으므로 가장 먼저 할
가치가 있다. 과거 snapshot read compatibility는 이 변환 경계의 회귀 테스트로 보존한다.

### 2. integration datasource 준비는 Testcontainers로 없앤다

현재 `PostgresIntegrationTestSupport`는 `INTEGRATION_DATASOURCE_URL`, `INTEGRATION_DATASOURCE_PASSWORD`, 별도
`agent_store_integration` DB와 profile을 외부에서 준비해야 한다. 이 보호 장치는 공유 개발 DB를 잘못 지우지 않게 하지만, 테스트 실행
방법 자체가 infra와 개발자 로컬 상태에 결합된다.

Spring Boot의
[`@ServiceConnection`](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html#testing.testcontainers.service-connections)은
`PostgreSQLContainer`의 connection details를 datasource auto-configuration에 직접 공급한다. 테스트 suite가 소유하는
`postgres:17` container를 한 번 띄우고 DB 이름을 `agent_store_integration`으로 고정하면 다음을 제거할 수 있다.

- integration datasource URL/password 환경변수
- 사람이 별도 DB를 만들고 비어 있는지 확인하는 실행 절차
- 개발 Compose DB와 integration DB를 구분하기 위한 외부 설정

Flyway 전체 migration, 실제 PostgreSQL lock/concurrency 검증과 tracked-fixture cleanup은 그대로 둔다. container가 test process 전용이면
`SPRING_EXCLUSIVE_MAINTENANCE` 같은 수동 독점 실행 확인도 목적을 다시 검토할 수 있다. Docker가 없는 환경에서는 integration suite만
기존처럼 opt-in으로 유지하면 된다.

### 3. Version constraint를 resolver 밖의 값 객체로 만든다

`DependencyResolver`는 graph 순회, direct/function dependency 해석, 후보 거절 사유, 공급자 정렬 외에 Python식 Version constraint의
정규화·문법 검증·SemVer 비교까지 소유한다. `DependencyService`, `QuoteService`, `ExternalInvocationService`는 raw `String`을
주고받으므로 같은 값이 유효하다는 사실을 타입으로 표현하지 못한다.

`VersionConstraint.parse(raw)`가 canonical expression과 predicates를 한 번 만들고 `matches(semver)`를 제공하는 immutable value
object가 적절하다. HTTP/manifest 경계에서 한 번 parse하고 resolver는 이미 검증된 값만 받는다. 이는 generic rule engine이나 새 SemVer
framework를 추가하는 작업이 아니며, 현재 사용하는 `semver4j` 위에서 독립적인 재사용 알고리즘 하나만 분리하는 것이다.

### 4. Spring Security는 인증까지만 옮긴다

`RuntimeCallbackController`는 `Authorization` 문자열을 받고, `RuntimeCallbackService`가 Bearer prefix 제거와 HMAC token
검증까지 수행한다. 반면 Spring이 demo-agent를 호출할 때 `X402AgentClient`는 `X-AgentStore-Invocation-Token`을 보내고 Go는 원
invocation의 `Authorization`을 callback에 전달한다. 먼저 한 가지 header 계약으로 통일해야 한다.

그 뒤 callback 경로에만 작은 `SecurityFilterChain`을 적용하면 header parsing, credential 검증, 인증 실패 응답과 principal 전달을
request boundary로 모을 수 있다. Spring Security의 servlet 인증은
[`SecurityFilterChain`, `AuthenticationManager`, `Authentication`](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html)
경계로 이 책임을 분리한다.

Spring Security가 다음 도메인 검증을 대신하면 안 된다.

- token의 execution/step/version/call path와 DB row 일치
- 실행과 parent step의 active 상태
- 선언된 dependency, budget, idempotency와 terminal race
- x402 signature·challenge·settlement 검증

현재 보호할 endpoint가 callback 하나뿐이면 starter, filter, provider, entry point가 기존 몇 줄보다 많아질 수 있다. header 불일치는
즉시 수정하되, 외부 receipt 인증이나 사용자 인증까지 공통 보안 경계가 두 곳 이상 생길 때 Spring Security를 도입한다. JWT/OAuth2
resource server로 token 형식을 바꾸는 것은 별도 요구가 없는 한 하지 않는다.

### 5. 선언형 HTTP client는 안전 경계가 단순한 호출에만 사용한다

Spring Framework는 annotated interface를 `RestClient` 등에 연결하는
[`HTTP Service Client`](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-http-interface)를 제공한다.
요청 경로와 DTO binding이 대부분인 API에는 boilerplate를 줄일 수 있다.

현재는 Bithumb 환율 조회 정도만 후보이고 우선순위는 낮다. `X402AgentClient`, `FacilitatorIncomingPaymentClient`와 pinned endpoint
client에는 DNS pinning, redirect 금지, body/deadline 제한, raw header 처리와 결제 결과 unknown 분류가 있다. 이 책임은 proxy interface로
숨겨도 없어지지 않으며 잘못 숨기면 안전 경계만 읽기 어려워진다. 이 client들은 수동 transport를 유지한다.

### 6. `afterCommit + @Async`는 단순 event annotation으로 바꾸지 않는다

`ExecutionService`는 transaction commit 뒤 `ExecutionRunner.start()`를 부르고, `ExecutionEventService`도 event 저장 commit 뒤
SSE broker에 publish한다. `@TransactionalEventListener(AFTER_COMMIT)`로 바꾸면 수동 synchronization 코드는 줄지만 process crash 때
작업이 사라지는 성질은 같아서 구조만 이동한다.

실행을 재시작 후 반드시 이어가야 한다는 요구가 생기면 DB job/outbox가 먼저다. Spring Modulith의
[`Event Publication Registry`](https://docs.spring.io/spring-modulith/reference/events.html#event-publication-registry)는 원 transaction에
publication을 기록하고 incomplete publication을 다시 제출할 수 있으므로 검토 후보가 될 수 있다. 그러나 현재 두 개의 `afterCommit`
호출만 치환하려고 도입하면 library schema, serialization, cleanup, 재처리 정책이 새로 생긴다. 요구가 생기기 전에는 도입하지 않는다.

payment journal과 reconciliation은 generic outbox나 retry로 대체하지 않는다. 결제 결과가 불명확한 상태에서 자동 재호출하면 이중 결제로
이어질 수 있기 때문이다.

## 도입하지 않을 기술

- **Coroutine, WebFlux, R2DBC**: virtual thread 기반 Spring MVC/JPA와 Go의 sibling callback 병렬화가 이미 역할을 나눠 가진다. reactive
  stack을 섞으면 transaction·MDC·blocking client 경계만 늘어난다.
- **Spring Statemachine, Temporal, Kafka**: 현재 execution/payment 상태를 제거하지 못하고 별도 상태 저장소와 복구 의미를 하나 더 만든다.
- **범용 retry/Resilience4j를 결제 호출에 적용**: read-only 환율 조회에는 검토할 수 있지만 Agent invoke·verify·settle에는 unknown outcome
  정책이 우선이다.
- **Bucket4j/Redis rate limiter**: 현재 단일 인스턴스 개발 환경의 `ExternalIntentRateLimiter`는 약 30줄이다. 분산 rate limit 요구 없이
  dependency와 Redis lifecycle을 추가하면 더 복잡하다.
- **MapStruct, QueryDSL, 범용 rules engine**: 현재 mapping/query/policy 규모에서는 annotation processing과 DSL 학습 비용이 대체할
  코드보다 크다.

## 줄이면 안 되는 복잡도

다음은 코드량이 많아도 제품 차별점 또는 금전 안전 경계이므로 단순 CRUD처럼 축소하면 안 된다.

- Base Sepolia USDC x402 v2 exact/EIP-3009 challenge 일치 검증과 native signing boundary
- payment intent, journal, reservation, transaction hash, revenue projection의 crash-window 복구
- 결제 결과가 불명확할 때 재결제하지 않는 reconciliation 정책
- quote 발급 시 Agent, Version, endpoint, 계약, 가격과 최대 비용을 고정하는 snapshot
- callback token 인증, admission lock과 terminal race 처리
- persisted SSE sequence, replay/live dedupe와 terminal close
- 결제 전 input Schema와 결제 후 output Schema 검증
- DNS pinning, redirect 금지, body/deadline 제한과 production endpoint 정책

이 경계들은 클래스 수를 줄이기 위해 합치기보다 상태 전이와 lock order가 읽히도록 유지해야 한다.

## 후속 적용 순서

1. quote snapshot read boundary를 typed DTO로 통일한다.
2. PostgreSQL integration suite를 Testcontainers service connection으로 자급식으로 만든다.
3. `VersionConstraint` 값 객체를 분리한 뒤 `DependencyResolver`가 graph/provider 선택만 소유하게 정리한다.
4. invocation token header 계약을 먼저 통일하고, 인증 경계가 확장될 때 Spring Security를 도입한다.
5. crash 후 execution 재개가 제품 요구가 될 때 durable dispatch를 별도 HIGH_RISK 작업으로 설계한다.

각 변경은 한 번에 묶지 않는다. typed snapshot은 quote/callback compatibility, Testcontainers는 migration·lock test 격리,
Version constraint는 API/manifest/quote 의미 보존, Security는 401/403/CommonResponse와 callback race, durable dispatch는 중복 실행과
payment recovery를 각각 독립적으로 검증한다.

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
