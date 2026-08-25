# AgentStore BE 인수인계서

최종 갱신: 2026-08-25

## 2026-08-25 catalog 전환

- Flyway V21이 `agents.slug`를 `agents.code`로 rename한다. 공개 API, OpenAPI, quote snapshot, FE와 Go runtime은 모두
  `agentCode`/`targetAgentCode`를 사용한다. 기존 snapshot의 `agentSlug`/`targetAgentSlug`는 `@JsonAlias`로 읽기만 지원한다.
- Flyway V22는 저장된 legacy `*`/exact/`^`/`~` Version constraint를 동등한 Python식 비교 조건으로 변환한다. 새 입력은
  `*`, `==1.0.0`, `>=1.0.0,<2.0.0`만 허용하며, 과거 Quote snapshot은 실행 계약으로 보존한다.
- `demo-agent/internal/catalog`은 투자·쇼핑·여행 13개 Agent의 runtime prompt, Schema와 fixture 정의를 가진다. Spring의
  `DemoCatalogInitializer`는 같은 catalog의 Marketplace 등록용 projection을 개발 DB에 생성한다. Root 3개는 user-facing
  Markdown, 나머지는 internal-component JSON이다.
- `dev` profile의 `DemoCatalogInitializer`가 `agents` count가 0일 때만 catalog를 직접 생성한다. 기존 registry는 변경하지 않는다.

### AgentCode/catalog HIGH_RISK failure matrix

| ID | 실패 경계·불변식 | 회귀 검증 |
|---|---|---|
| AC-BE-01 | V21은 기존 `agents.slug` 값·unique index를 `code`로 보존하며 적용된 migration을 바꾸지 않는다. | `PostgresSchemaIntegrationTest`의 V21 scratch-schema data/index 검증과 current Flyway schema 검증 |
| AC-BE-02 | 과거 quote JSONB의 `agentSlug`/`targetAgentSlug`는 읽을 때 canonical `agentCode`/`targetAgentCode`가 되어 root 실행·callback path에서 빈 값이 되지 않는다. | `QuoteSnapshotCompatibilityTest`, `ExecutionCapabilitySchemaTest`, `PostgresSimulatedRuntimeE2eIntegrationTest` |
| AC-BE-03 | runtime JSON 결과의 business `output` 필드는 보존하고, bundled demo-agent의 `agent`·`dependencyResults` transport envelope만 unwrap한다. | `RuntimeOutputEnvelopeTest`, `PostgresSimulatedRuntimeE2eIntegrationTest` |
| AC-BE-04 | dev initializer는 registry가 비었을 때만 고정 demo catalog를 생성하고, 기존 Agent가 있으면 변경하지 않는다. | `DemoCatalogInitializerTest` |
| AC-BE-05 | catalog 생성은 기존 Agent/Version/Dependency service 경계를 통해 하나의 transaction으로 처리되고, empty registry에서만 시작된다. | `DemoCatalogInitializerTest` |
| AC-BE-06 | public API/OpenAPI는 `{code}` 계약과 `CommonResponse`만 노출하며 demo catalog용 HTTP endpoint는 없다. | Spring `/openapi.json` 재생성 뒤 `openapi/openapi.json` parity 검사와 FE `npm run api:generate` |
| AC-BE-07 | Dependency·Quote·외부 invocation은 같은 Python식 comparator parser를 사용하고, V22는 legacy 범위를 의미 보존 변환한다. | `DependencyResolverVersionConstraintTest`, `PostgresSchemaIntegrationTest` V22 scratch-schema 검증 |

## 저장소와 역할

- 경로: `C:\Users\we661\IdeaProjects\agent-store-be`
- 스택: Kotlin/Spring + Java JPA Entity/VO/Enum, PostgreSQL/Flyway, Web3j native x402
- FE: `C:\Users\we661\WebstormProjects\agent-store-fe`
- API OpenAPI artifact: `openapi\openapi.json`

## 구조 규칙

- `eco-knock-be-central` 스타일을 유지한다: 도메인별 layered package, Java Entity/VO/Enum, Kotlin
  Controller·Service·DTO·Repository interface.
- 모든 Entity는 `BaseEntity`를 상속하며, 관계는 scalar UUID FK로 표현한다. `@ManyToOne`은 사용하지 않는다.
- 방향은 `Controller → Service/Orchestrator → Repository/Client/Resolver`다. 다른 도메인 Repository를 직접 주입하지
  않는다.
- public JSON은 `CommonResponse<T>` envelope을 사용하고, runtime callback은 public OpenAPI에서 숨긴다.
- Kotlin 함수는 block body를 사용하고 companion object는 클래스 상단에 둔다. 여러 인자의 Kotlin 호출은 named
  argument를 사용하며 Java 호출만 언어 제약상 예외다.
- 설정에는 환경변수 fallback을 두지 않는다. Spring `.env`는 password·token·private key만 두고, 공개 URL·지갑 주소·port·timeout·TTL·fee·rate limit·payment mode 같은 값은 YAML에 명시한다. Docker Compose가 직접 읽는 값만 `.env` 예외로 둔다.

## 현재 구현 상태

- Function Contract API와 manifest import/export가 추가됐다. 공개 API는 사람이 읽는 kebab-case function code와
  `inputSchema`/`outputSchema` 계약만 제공하며, 구형 capability endpoint·dependency field는 제거됐다.
- DRAFT Version dependency는 `pinned`·`allowlist`·`marketplace` provider scope와
  `lowest_price`·`latest_version`·`highest_reliability`·`fastest`·`balanced` strategy를 선언할 수 있다.
  provider 선택은 Quote ID seed를 포함한 Quote-time resolver에서만 수행하며, selected provider·후보
  metrics·선택 이유는 snapshot으로 고정된다. 실행 중 fallback은 없다.
- 30일 execution-step observation으로 Wilson lower-bound 신뢰도, 성공 호출 p95 latency, output contract
  compliance를 계산한다. 20건 미만 provider는 metric strategy에서 explicit exploration으로만 선택될 수
  있고 payment/reconciliation/platform outcome은 공급자 reliability 분모에서 제외된다.
- Flyway V16~V19는 manifest, function provider selection, observation schema를 추가하고 구형
  `target_capability_id`·`selection_policy` 열과 enum을 제거했다. V19의 DB constraint는 direct dependency 또는
  완전한 function provider declaration 중 하나만 저장하도록 강제한다.

- Quote snapshot의 각 resolved Version에 Agent 공개 `agentDescription`을 nullable로 고정한다. 새 실행은 실행 당시 설명을 재현하고, 과거 snapshot은 필드 누락 상태로 계속 역직렬화된다.

- immutable function contract의 Schema는 64 KiB·깊이 32 제한, 원격 `$ref` 금지와 Draft 2020-12 검증을 적용한다.
- function dependency는 `lowest_price`·`latest_version`·`highest_reliability`·`fastest`·`balanced` 전략으로 Quote 때 공급자를 결정하며 후보 수, Version·가격·cycle·하위 graph 한도를 검사한다.
- Quote snapshot은 계약 Schema, 후보별 제외 사유, 선택 Version과 이유를 보존한다. 실행 중 다른 공급자로 fallback하거나 재결제하지 않는다.
- root/dependency input은 reservation·결제 전에, output은 결제 및 형식 검증 뒤 function contract Schema로 검사한다. 출력 위반은 결제 증거를 보존하고 `AGENT_OUTPUT_SCHEMA_INVALID`로 terminalize한다.
- `ExecutionResponse.quoteSnapshot`으로 새로고침 뒤에도 선택과 거래 graph를 복원한다.
- PostgreSQL opt-in reference E2E는 서로 다른 developer·endpoint·`payTo`의 root, function provider 둘, direct 공급자를 구성한다. `lowest_price` 선택 뒤 더 우선적인 Version이 publish돼도 기존 snapshot을 실행하고, 선택된 세 개발자에게만 1000·900·1000 atomic 수익이 귀속됨을 검증한다.

- `/v1/invocation-intents` 외부 x402 호출은 AgentStore의 기본 공개 API다. 외부 개발자는 API key 없이 direct Agent 또는
  Function Contract를 선택하고, AgentStore가 고정한 Base Sepolia USDC EIP-3009 `exact` requirement에 결제한다. intent는
  Quote·입력·provider cost·platform fee·총액을 결제 전에 고정하며, idempotency key별 PostgreSQL transaction advisory lock으로
  중복 Quote를 막는다. receipt token은 header로 한 번만 반환하고 hash만 저장한다. incoming settlement이 영속된 뒤에만 내부
  Execution을 만들며, 불명확한 facilitator 결과는 `reconciliation_required`로 유지하고 재결제·fallback하지 않는다.

- Registry·Dependency·Quote·Revenue·simulated Execution/SSE/runtime callback Spring 이식이 존재한다.
- Marketplace `GET /api/agents`는 cursor/limit에 더해 `q`, `sort=newest|name_asc`를 지원한다.
- 목록 cursor는 q/sort에 binding된 HMAC keyset cursor다. 삭제·비활성화된 cursor row 뒤에도 페이지가 지속된다.
- Marketplace 목록은 ACTIVE version만 노출하며 `dependencyCount`는 distinct target Agent 수다.
- Agent Version에는 `AgentResponseFormat`(TEXT, MARKDOWN, STRUCTURED, JSON)이 저장되며 기존 행과 누락 요청은 JSON으로
  보정된다. 등록/Version/quote snapshot/execution step 응답에 같은 값이 전파된다.
- 실행 시 선언 형식과 output을 검증한다. TEXT·MARKDOWN은 JSON 문자열, STRUCTURED는 title과 하나 이상의 sections를 요구하며 불일치 시
  `AGENT_OUTPUT_FORMAT_INVALID`로 실패 처리한다. 결제 기록은 보존하고 자동 재시도하지 않는다.
- 목록 구현은 Spring unit test, `classes`, `bootJar`, fresh read-only verifier를 통과했다.
- `CommonResponse`, 예외 계층, `ErrorCode`, global handler는 Kotlin이다. Marketplace query는 request DTO로
  binding하며 wire sort 값은 `newest|name_asc`다.
- datasource는 Spring 표준 `SPRING_DATASOURCE_*`를 사용한다. 커스텀 DB URL parser, compatibility baseline
  validator, production `common/migration`, `application-postgres-integration.yaml`은 제거했다.
- `verifyProjectStyle`가 expression body, late companion, production wildcard/FQ reference, 환경변수 fallback,
  redundant `@Param`/`@Column(name)`, 금지된 migration/config 경로를 빌드에서 차단한다.
- OpenAI demo-agent의 `financial`, `news`, `risk`는 Responses 웹 검색을 병렬 callback으로 실행하고, 검증된 HTTPS 출처를
  최대 5개씩 전달한다. `investment`는 이를 종합한 한국어 Markdown과 3~5개 출처 링크를 반환한다.
- 로컬 DB의 `investment` `1.2.0`은 `MARKDOWN` ACTIVE Version이며 기존 `1.1.0`은 JSON snapshot 보존을 위해 DISABLED 상태다.
  실행 runner는 질문과 원본 input을 root·runtime dependency에 같은 컨텍스트로 전달하고 Markdown Version은 Go HTTP envelope의
  `output` 문자열만 실행 결과로 저장한다.

## 현재 상태와 다음 순서

Node x402 bridge와 backend 내 demo-agent workspace를 제거하고 Spring native x402 v2 client로 교체했다. Demo agent는
`C:\Users\we661\GolandProjects\demo-agent`의 독립 Go 서비스다. `agent-store.payment-mode=x402`는 Base Sepolia 기본
USDC의 `exact`/EIP-3009만 지원하고 `X402_PRIVATE_KEY`를 Spring secret으로 직접 주입한다. Quote와 402 challenge
조건을 모두 대조한 뒤 EIP-712 서명을 만들며 Permit2와 그 외 transfer method는 서명 전에 거절한다. 기존
endpoint DNS pinning, redirect 금지, 30초 timeout, 1 MiB 제한, 동일 attempt/key in-flight correlation과
unknown
결제의 reservation 보존 정책은 유지한다. JVM restart 뒤 in-memory settlement evidence가 없으면 recovery는
재결제하지 않고 계속 `UNKNOWN`을 반환한다.

로컬 PostgreSQL의 Flyway V11/V12 history checksum은 현재 immutable migration과 불일치했으나, schema/data를 바꾸지 않는
Flyway `repair`로 history만 동기화했다. V11의 `payment_attempts.projected_at` 존재를 확인했고, V12가 backfill 대상으로
삼는 조건은 변경하지 않았다. Spring은 단독 DB writer로 기동해 20개 migration validation과 Hibernate validation을 통과했으며
`/openapi.json`을 재생성했다.

재생성 artifact의 `GET /api/agents`에는 `limit`, `cursor`, `q`, `sort` query와
`AgentResponse.dependencyCount`가 포함된다. 응답 형식 필드를 포함한 OpenAPI를 다시 생성했고 FE client도 재생성했다. Spring은 V13
migration, Hibernate schema validation, output validator 단위 테스트를 통과했다. Spring의 PostgreSQL
maintenance guard는 제거됐으므로, 일반 `bootRun`은 `RUN_POSTGRES_INTEGRATION_TESTS` 및
`SPRING_EXCLUSIVE_MAINTENANCE` 없이 표준 `SPRING_DATASOURCE_*` 설정으로 바로 기동한다. PostgreSQL integration test의 opt-in flag는
fixture가 실제 DB를 변경하므로 유지한다.
재생성 artifact의 `GET /api/agents`에는 `limit`, `cursor`, `q`, `sort` query와
`AgentResponse.dependencyCount`가 포함된다. 응답 형식 필드를 포함한 OpenAPI를 다시 생성했고 FE client도 재생성했다. Spring은 V13
migration, Hibernate schema validation, output validator 단위 테스트를 통과했다. 독립 fresh verifier는
response-format terminalization과 failure matrix를 PASS로 확인했다. Spring의 PostgreSQL maintenance guard는
제거됐으므로, 일반 `bootRun`은 `RUN_POSTGRES_INTEGRATION_TESTS` 및 `SPRING_EXCLUSIVE_MAINTENANCE` 없이
표준 `SPRING_DATASOURCE_*` 설정으로 바로 기동한다. PostgreSQL integration test의 opt-in flag는 fixture가 실제 DB를 변경하므로 유지한다.

## 안전 경계

- Spring만 데이터베이스를 write한다.
- Flyway history, 적용된 migration, DB data는 명시적 승인 없이 수정·repair·baseline하지 않는다.
- 결제 unknown 상태는 재결제·예약 해제·수익 생성 없이 reconciliation 대상으로 유지한다.
- `X402_PRIVATE_KEY`, EIP-712 typed data, signature와 raw x402 header를 저장하거나 로그로 출력하지 않는다.

## 검증 명령

```powershell
.\gradlew.bat test
.\gradlew.bat classes
.\gradlew.bat bootJar
git diff --check
```
