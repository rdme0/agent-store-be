# AgentStore BE

AgentStore는 **AI Agent를 등록하고, Agent끼리 의존성을 연결하고, 실행 전에 최대 비용을 확정한 뒤, 실행·결제·수익을 추적하는 Marketplace**
입니다. 이 저장소는 계약과 실행, native x402 결제를 책임지는 Kotlin/Spring 백엔드이며 PostgreSQL과 선택적인 demo agent와 함께 동작합니다.

이 문서는 현재 구현만 설명합니다. 미래 계획이나 아직 연결되지 않은 기능은 포함하지 않습니다.

## 1. 가장 쉽게 이해하기

일반 앱스토어가 앱을 판매한다면 AgentStore는 호출 가능한 Agent를 판매합니다. 한 Agent가 일을 끝내기 위해 다른 Agent를 호출할 수 있다는 점이 핵심입니다.

1. 개발자가 Agent와 Version을 등록하고 `ACTIVE`로 publish합니다.
2. 사용자가 Marketplace에서 Agent를 고릅니다.
3. 서버가 모든 의존성 Version과 최대 비용을 확정한 Quote를 발급합니다.
4. 사용자가 Maximum Cost를 승인합니다.
5. 서버가 실행하면서 각 Agent 호출과 결제를 기록합니다.
6. 브라우저는 SSE로 과정을 실시간 표시합니다.
7. 성공한 결제는 개발자의 direct/dependency 수익으로 집계됩니다.

```mermaid
flowchart LR
    Dev["Agent 개발자"] -->|Agent와 Version 등록| Registry["Agent Registry"]
    User["사용자"] -->|Agent 선택| Market["Marketplace"]
    Market -->|Quote 요청| Resolver["의존성 Resolver"]
    Registry --> Resolver
    Resolver -->|고정된 Version과 최대 비용| Quote["5분 유효 Quote"]
    User -->|Maximum Cost 승인| Quote
    Quote --> Execution["Execution"]
    Execution --> Root["Root Agent"]
    Root --> Dep["Dependency Agent"]
    Execution --> Payment["결제와 정산"]
    Payment --> Revenue["개발자 수익"]
```

## 2. 전체 구조와 프로세스 경계

```mermaid
flowchart TB
    Browser["브라우저\nReact FE :5173 또는 :5174"]
    Spring["AgentStore API\nSpring Boot :8080"]
    DB[("PostgreSQL\nRegistry, Quote, Execution, Payment, Revenue")]
    Demo["Demo Agent\nGo/Gin :8090"]
    Facilitator["x402 Facilitator"]
    Chain["Base Sepolia"]
    Browser -->|JSON API와 SSE| Spring
    Spring -->|JPA transaction| DB
    Spring -->|simulated 또는 EIP - 3009 서명 호출| Demo
    Demo -->|verify / settle| Facilitator
    Facilitator -->|USDC settlement| Chain
```

| 구성 요소       | 책임                                                               | 가지면 안 되는 책임               |
|-------------|------------------------------------------------------------------|---------------------------|
| React FE    | 목록, 입력, 승인, 실행 상태 표현                                             | DB 접근, private key, 결제 서명 |
| Spring API  | 검증, Version 해석, Quote, 예산, 실행, 복구, 수익, 전용 hot-wallet EIP-3009 서명 | 사용자 임의 지갑 관리              |
| PostgreSQL  | 영속 상태와 복구 근거                                                     | 외부 Agent 호출               |
| Go Demo Agent | 로컬 실행 대상과 x402 응답 시뮬레이션                                       | Marketplace 원장 관리         |

Spring API는 `8080`, 선택적인 Go demo-agent는 `8090`입니다. 운영 결제에 별도 런타임은 필요하지 않습니다.

## 3. 핵심 도메인

### Agent와 Version

- `Agent`는 이름, 설명, URL용 `slug`, 소유 개발자를 가집니다.
- 실제 endpoint, 가격, 결제 계약은 `AgentVersion`에 있습니다.
- Version 상태는 `DRAFT → ACTIVE → DISABLED`입니다.
- Marketplace와 Quote resolver는 `ACTIVE` Version만 사용합니다.
- `slug`는 소문자 영숫자와 하이픈 조합이며 최대 80자입니다.
- `priceAtomic`은 숫자로만 된 문자열입니다. 부동소수점 반올림을 피하려고 JSON number를 쓰지 않습니다.
- `responseFormat`은 Version이 반환할 결과 표현을 선언합니다. `TEXT`, `MARKDOWN`, `STRUCTURED`, `JSON` 중 하나이며, 생략된
  기존 Version은 `JSON`으로 취급합니다.
- `TEXT`와 `MARKDOWN`은 문자열을, `STRUCTURED`는 `title`과 하나 이상의 `{label, value}` 섹션을 요구합니다. 섹션 값은
  문자열·숫자·불리언만 허용합니다. `JSON`은 임의의 JSON을 허용합니다.

```mermaid
stateDiagram-v2
    [*] --> DRAFT: Version 생성
    DRAFT --> ACTIVE: publish
    ACTIVE --> DISABLED: disable
    DISABLED --> [*]
```

### Dependency

| 필드                  | 의미                              |
|---------------------|---------------------------------|
| `targetAgentId`     | 호출할 대상 Agent                    |
| `versionConstraint` | exact, `^`, `~`, `*` Version 범위 |
| `required`          | 해석 실패 시 Quote 전체를 실패시킬지 여부      |
| `maxPriceAtomic`    | 선택할 dependency 가격 상한            |
| `maxCalls`          | 이 edge의 최대 호출 수, 1~5            |

필수 dependency의 Version을 찾지 못하면 Quote가 실패합니다. 선택 dependency의 Version을 찾지 못한 경우에만 warning
`OPTIONAL_DEPENDENCY_NOT_RESOLVED`를 남깁니다. Version은 찾았지만 `maxPriceAtomic`을 넘으면 required 여부와 관계없이
`DEPENDENCY_PRICE_EXCEEDED`로 Quote 전체를 거부합니다. 순환 참조는 전체 경로와 함께 거부되며, 호출 경로는 root를 포함해 최대 5개
Agent입니다.

### Quote는 견적이면서 실행 계약이다

Quote는 선택된 정확한 Version, endpoint, 가격, payment term, dependency graph를 snapshot으로 고정합니다. Quote 뒤에 새
Version이 publish되어도 이미 발급된 Quote의 의미는 바뀌지 않습니다.

```mermaid
flowchart TD
    Start["slug와 Version constraint"] --> Root["가장 높은 ACTIVE root Version"]
    Root --> Resolve["dependency별 가장 높은 ACTIVE Version 탐색"]
    Resolve --> Found{"Version을 찾았나?"}
    Found -->|아니오, required| Reject["Quote 거부"]
    Found -->|아니오, optional| Warn["warning 기록 후 해당 edge 제외"]
    Found -->|예| Cycle{"cycle 또는 깊이 초과?"}
    Cycle -->|예| Reject["Quote 거부"]
    Cycle -->|아니오| Limit{"가격 상한 충족?"}
    Limit -->|아니오| Reject
    Limit -->|예| Cost["node 가격 + child 비용 × maxCalls"]
    Warn --> Cost
    Cost --> Guard{"step 32개 이하, 총액 10^60 이하?"}
    Guard -->|아니오| Reject
    Guard -->|예| Snapshot["정확한 graph snapshot 저장"]
    Snapshot --> Expiry["5분 뒤 만료"]
```

최대 비용은 각 node 가격에 child 비용과 edge의 `maxCalls`를 곱해 누적합니다. 최대 32 step이며 계산값이 `10^60`을 넘으면 거부합니다.

### Execution과 Step

사용자는 Quote ID, 질문, `maxBudgetAtomic`을 제출합니다. 예산은 Quote의 `maxCostAtomic`과 **정확히 같아야** 합니다. Execution,
root Step, 최초 event는 한 transaction에서 저장되고 실제 runner는 `afterCommit`에서만 시작합니다. commit되지 않은 실행이 외부
Agent를 호출하는 일을 막는 경계입니다.

```mermaid
sequenceDiagram
    participant FE as Browser
    participant API as Spring API
    participant DB as PostgreSQL
    participant Root as Root Agent
    participant Dep as Dependency Agent
    FE ->> API: POST quote
    API ->> DB: graph snapshot 저장
    API -->> FE: Quote와 maxCostAtomic
    FE ->> API: POST execution, 동일한 maxBudgetAtomic
    API ->> DB: Execution, root Step, event 저장
    DB -->> API: COMMIT
    API -->> FE: Execution ID
    API ->> Root: commit 이후 invoke
    Root ->> API: dependency callback와 idempotency key
    API ->> DB: frozen snapshot, 예산, 중복 확인
    API ->> Dep: 허용된 dependency invoke
    Dep -->> API: result
    API ->> DB: step, payment, event, revenue 기록
    API -->> FE: SSE event
```

Execution은 `PENDING → RUNNING → COMPLETED/FAILED`입니다. Step은 `CREATED`, `PAYMENT_REQUIRED`,
`PAYMENT_SETTLED`, `RUNNING`, `COMPLETED`, `FAILED`를 사용하고 Payment는 `REQUIRED`, `AUTHORIZED`,
`SETTLED`, `FAILED`, `RECONCILIATION_REQUIRED`를 사용합니다. 전체 실행, 개별 Step, Payment 상태가 같다고 가정하면 안 됩니다.

Agent output이 Version의 `responseFormat`과 맞지 않으면 결제 기록을 보존한 채 Step과 Execution을 `FAILED`로 전환하고
`AGENT_OUTPUT_FORMAT_INVALID`를 failure code로 기록합니다. 이 검증은 root Agent와 runtime dependency Agent 모두에
적용됩니다.

### Runtime dependency callback

내부 callback은 `POST /api/runtime/executions/{id}/dependencies/invoke`입니다.

- Bearer invocation token과 `Idempotency-Key`가 모두 필요합니다.
- token의 execution, parent step, Version, call path가 DB와 일치해야 합니다.
- Execution은 `RUNNING`, parent step은 활성 상태여야 합니다.
- dependency가 Quote의 frozen snapshot에 있어야 합니다.
- 요청 경로는 parent 바로 다음 node이며 최대 깊이를 넘을 수 없습니다.
- 완료된 동일 요청은 기존 결과를 반환하고 진행 중 중복은 새 호출을 만들지 않습니다.

즉 Agent가 견적에 없던 고가 Agent를 끼워 넣거나 재시도로 이중 호출하는 것을 막는 admission gate입니다.

## 4. 결제, 장애, 복구

- `agent-store.payment-mode=simulated`: 실제 chain 결제 없이 동일한 원장 흐름을 검사합니다.
- `agent-store.payment-mode=x402`: Spring이 전용 hot-wallet private key로 Base Sepolia USDC EIP-3009 payload를 직접
  서명합니다.

네트워크 timeout은 실패를 뜻하지 않습니다. 외부 결제는 성공하고 응답만 유실될 수 있어 서버는 side effect 전에 payment intent와 budget
reservation을 먼저 영속화합니다.

```mermaid
flowchart TD
    Reserve["Payment intent와 예산 reservation 저장"] --> Call["Agent 402 challenge 요청"]
    Call --> Validate["Quote와 challenge 조건 대조"]
    Validate --> Sign["EIP-3009 서명 후 paid invoke"]
    Sign --> Known{"settlement 증거가 명확한가?"}
    Known -->|성공| Settle["SETTLED를 정확히 한 번 projection"]
    Known -->|timeout 또는 UNKNOWN| Reconcile["RECONCILIATION_REQUIRED 유지"]
    Known -->|settled 아님| Reconcile
    Reconcile --> Check["native in-memory receipt correlation"]
    Check -->|settled 증명| Settle
    Check -->|SETTLED 이외 모든 결과| Reconcile
    Settle --> Local{"로컬 step와 revenue 반영 성공?"}
    Local -->|예| Continue["실행 계속"]
    Local -->|아니오| AfterPay["FAILED_AFTER_PAYMENT\n증거와 reservation 보존"]
```

서명 이후 receipt가 없거나 불명확하면 `RECONCILIATION_REQUIRED`와 reservation을 유지합니다. JVM 안의 동일 attempt/key
correlation이 성공 receipt를 증명할 때만 settlement를 복구하며, restart로 증거가 사라지면 재결제하지 않고 `UNKNOWN`을 유지합니다.
settlement 뒤 로컬 projection이 실패해도 결제 사실을 지우지 않아 복구 근거를 보존합니다. private key, typed data, signature와 raw
x402 header는 DB나 로그에 기록하지 않고 FE 환경 변수나 Git에 넣지 마세요.

## 5. SSE 실시간 이벤트

event는 DB에 먼저 저장한 후 publish합니다. `GET /api/executions/{id}/events`가 과거 replay와 live stream을 연결합니다.

- 증가 sequence와 SSE ID를 사용합니다.
- `Last-Event-ID` 이후부터 replay합니다.
- replay/live가 겹쳐도 ID와 sequence로 중복 적용하지 않습니다.
- terminal event 뒤 stream을 닫습니다.
- 브라우저 연결이 끊겨도 서버 실행은 계속됩니다.

## 6. Marketplace 목록의 작은 규칙

`GET /api/agents`는 `q`, `sort`, `cursor`, `limit`을 받습니다.

- `sort`: `newest` 또는 `name_asc`.
- ACTIVE Version이 하나 이상 있는 Agent만 반환합니다.
- 항목의 Version 목록도 ACTIVE만 포함합니다.
- `dependencyCount`는 서로 다른 target Agent 수입니다.
- cursor는 검색어와 정렬 조건에 묶인 opaque HMAC 값입니다.
- 다른 조건에 cursor를 재사용할 수 없고 클라이언트가 내용을 해석하면 안 됩니다.
- 반환된 row가 나중에 삭제/비활성화돼도 다음 페이지를 이어 갈 수 있습니다.

## 7. API와 공통 응답

일반 JSON은 `CommonResponse<T>` envelope를 사용합니다. 추적 ID는 JSON이 아니라 `X-Trace-Id` header에 있습니다.

```json
{
  "isSuccess": true,
  "message": "요청이 성공했습니다.",
  "result": {}
}
```

| Method         | Path                                                   | 역할                 |
|----------------|--------------------------------------------------------|--------------------|
| GET            | `/health`                                              | 상태 확인              |
| GET / POST     | `/api/agents`                                          | 목록 / 등록            |
| GET            | `/api/agents/{slug}`                                   | 상세                 |
| PATCH / DELETE | `/api/agents/{id}`                                     | 수정 / 삭제            |
| POST           | `/api/agents/{id}/versions`                            | Version 생성         |
| POST           | `/api/agent-versions/{id}/publish`                     | 활성화                |
| POST           | `/api/agent-versions/{id}/disable`                     | 비활성화               |
| GET / POST     | `/api/agent-versions/{id}/dependencies`                | dependency 조회 / 추가 |
| PATCH / DELETE | `/api/agent-versions/{id}/dependencies/{dependencyId}` | 수정 / 삭제            |
| POST           | `/api/agents/{slug}/quotes`                            | Quote 발급           |
| POST / GET     | `/api/executions`, `/api/executions/{id}`              | 시작 / snapshot      |
| GET            | `/api/executions/{id}/events`                          | SSE                |
| GET            | `/api/developers/{id}/revenue`                         | 수익                 |

runtime callback은 일반 사용자용 API가 아닙니다.

## External x402 Invocation API

외부 개발자는 AgentStore 계정이나 영구 API key 없이도 Agent를 호출할 수 있습니다. 호출자는 한 번의 실행 intent를 만들고,
AgentStore가 제시한 Base Sepolia USDC x402 결제를 완료합니다. AgentStore는 받은 결제를 증명한 뒤에만 내부 Marketplace
Quote와 Execution을 만들고, 그 뒤 공급자 Agent 결제와 정산을 기존 실행 원장 안에서 처리합니다.

이 API는 AgentStore의 기본 공개 API이며, 서버가 기동되면 항상 노출됩니다. 아래 공개 설정은
`src/main/resources/application.yaml`에 명시하며, 모든 URL은 HTTPS 기본 port만 허용합니다. `pay-to`는
AgentStore가 받는 EVM 지갑입니다.

| YAML 키 | 설명 |
|---|---|
| `agent-store.external-api.public-base-url` | 외부 클라이언트가 실제로 접근하는 AgentStore HTTPS base URL |
| `agent-store.external-api.pay-to` | 외부 x402 USDC를 받는 AgentStore EVM 지갑 |
| `agent-store.external-api.facilitator-url` | `/verify`, `/settle`을 제공하는 HTTPS facilitator base URL |
| `agent-store.external-api.facilitator-request-timeout` | facilitator 요청 timeout (`PT5S` 형식) |
| `agent-store.external-api.authorization-timeout` | EIP-3009 authorization 유효 시간 (`PT60S` 형식) |
| `agent-store.external-api.fee-basis-points` | 공급자 Quote 비용에 더할 플랫폼 수수료 basis point |
| `agent-store.external-api.intent-ttl` | 결제 전 intent 유효 시간 |
| `agent-store.external-api.receipt-ttl` | 조회·SSE에 쓰는 1회 호출 receipt 유효 시간 |
| `agent-store.external-api.rate-limit-per-minute` | source IP 기준 intent 생성 한도 |

`POST /v1/invocation-intents`에는 길이 16~128의 `Idempotency-Key`를 보냅니다. 같은 key와 같은 본문은 같은 intent를
반환하고, 본문이 다르면 `409`입니다. `agentSlug` + `versionConstraint`로 특정 Agent를 고르거나, `functionCode` +
`contractVersion` + `selectionStrategy`로 Function Contract 공급자를 고릅니다. 둘을 함께 보낼 수 없습니다.

```json
{
  "agentSlug": "weather-summary",
  "versionConstraint": "*",
  "maxTotalAtomic": "1250000",
  "question": "서울 내일 날씨를 알려줘",
  "input": { "city": "Seoul" }
}
```

```json
{
  "functionCode": "weather.forecast-summary",
  "contractVersion": "1.0.0",
  "selectionStrategy": "lowest_price",
  "maxTotalAtomic": "1250000",
  "input": { "city": "Seoul" }
}
```

성공 응답은 `201`이며 `result`에 공급자 비용, 플랫폼 수수료, 총 atomic USDC 비용과 만료 시각을 넣습니다. 조회에 필요한
`X-AgentStore-Invocation-Receipt` header도 이때 한 번만 받습니다. 이 값은 bearer secret이므로 로그·브라우저 저장소·공개 URL에
넣지 마세요. 서버는 원문이 아니라 hash만 저장합니다.

다음으로 `POST /v1/invocation-intents/{intentId}/execute`에 receipt header를 넣어 보냅니다. 서명이 없으면 `402`와
`PAYMENT-REQUIRED` header를 반환합니다. 외부 x402 client는 이 header의 v2 `exact` requirement와 완전히 일치하는
Base Sepolia USDC EIP-3009 `PAYMENT-SIGNATURE`를 만들어 같은 요청에 다시 보냅니다. 서명 검증과 facilitator settlement가
성공하면 `202`, `PAYMENT-RESPONSE`, 그리고 내부 `executionId`를 반환합니다. timeout·연결 손실·누락 receipt는 성공으로
추정하지 않고 `reconciliation_required`가 되며 새 결제나 다른 공급자 fallback을 시작하지 않습니다.

```powershell
$headers = @{
  'Idempotency-Key' = 'external-weather-request-0001'
  'Content-Type' = 'application/json'
}

Invoke-RestMethod `
  -Method Post `
  -Uri 'https://api.example.com/v1/invocation-intents' `
  -Headers $headers `
  -Body '{"agentSlug":"weather-summary","versionConstraint":"*","maxTotalAtomic":"1250000","input":{"city":"Seoul"}}'
```

상태는 `GET /v1/invocation-intents/{intentId}`, 실시간 진행은 `GET /v1/invocation-intents/{intentId}/events`에서 같은
receipt header로 조회합니다. SSE는 기존 `Last-Event-ID` replay 규칙을 그대로 따릅니다. 최종 결과는 항상
`CommonResponse.result.output`에 들어가므로 외부 서비스는 실행 그래프가 아닌 Agent output만 간단히 소비할 수 있습니다.

## 8. 로컬 실행

개발 환경의 PostgreSQL, Spring API와 선택적인 Go demo-agent는 상위 폴더의
[`agent-store-infra`](../agent-store-infra)에서 함께 실행합니다. 프론트엔드는
`agent-store-fe`에서 로컬 Vite 서버로 실행합니다.

```powershell
Set-Location ../agent-store-infra
Copy-Item ../agent-store-be/.env.example ../agent-store-be/.env
Copy-Item ../demo-agent/.env.example ../demo-agent/.env
docker compose --env-file ../agent-store-be/.env up --build -d
```

Compose는 `pgvector/pgvector:pg17` PostgreSQL을 시작하고, 최초 volume 생성 시 `agent_store`와
`agent_store_integration` DB를 준비합니다. API는 Docker 전용 `docker` profile에서 Compose의 PostgreSQL에 연결하며,
개발 profile을 함께 활성화해 기존 loopback demo Agent endpoint를 유지합니다. API는
`http://localhost:8080`, OpenAPI는 `http://localhost:8080/openapi.json`, demo-agent는
`http://localhost:8090`에서 확인할 수 있습니다. Spring 시작 시 Flyway migration을 적용하고 Hibernate가 schema를
validate합니다. 이미 적용한 migration을 수정하지 말고 새 migration을 추가합니다.

| `.env` 값 | 용도 |
|---|---|
| `POSTGRES_PASSWORD` | Docker Compose PostgreSQL과 API의 개발용 PostgreSQL 비밀번호 |
| `RUNTIME_TOKEN_SECRET` | callback token과 cursor 서명 secret |
| `X402_PRIVATE_KEY` | x402 mode 전용 저잔액 payer key, `0x` + 64자리 hex |
| `POSTGRES_PORT` | `agent-store-infra` Docker Compose가 직접 읽는 PostgreSQL host port |

credentials를 허용하므로 CORS 전체 origin `*`는 사용할 수 없습니다. 로컬 기본 `http://localhost:*`는 Vite의 가변 port만 허용합니다.
운영에서는 `application.yaml` 또는 운영용 YAML의 `agent-store.cors-origins`를 정확한 HTTPS origin으로 제한합니다.

Demo Agent는 독립 Go 서비스이지만 개발 Compose에서는 API 컨테이너의 네트워크 네임스페이스를 공유합니다. 따라서
`127.0.0.1:8090` endpoint와 `127.0.0.1:8080` runtime callback은 컨테이너 안에서도 그대로 동작합니다. demo-agent의
설정 예시와 검증 명령은 해당 프로젝트 README가 소유하며, Spring의 `X402_PRIVATE_KEY`는 demo-agent에 복사하지 않습니다.

`agent-store.payment-mode=x402`에서 Spring은 `X402_PRIVATE_KEY`가 없거나 형식이 잘못되면 시작에 실패합니다. Base Sepolia 기본 USDC의 x402
v2 `exact`/EIP-3009만 지원하며 Permit2 challenge는 서명 전에 거절합니다. 실제 x402 smoke는 전용 지갑, facilitator, testnet
자금이 준비돼야 하며 funded Base Sepolia 성공을 보장하지 않습니다.

## 9. OpenAPI와 FE 계약

Spring controller/DTO가 계약의 원본입니다. 계약을 바꾼 뒤 서버를 실행하고 다른 terminal에서 아래처럼 정적 artifact를 갱신합니다. `bootRun`
만으로 파일이 자동 저장되지는 않습니다.

```powershell
Invoke-WebRequest http://localhost:8080/openapi.json -OutFile openapi\openapi.json
```

그다음 FE에서 생성 코드를 다시 만듭니다. FE의 `src/generated`는 직접 수정하지 않습니다.

## 10. 검증

```powershell
.\gradlew.bat classes
.\gradlew.bat test
.\gradlew.bat bootJar
git diff --check
```

PostgreSQL integration test는 일반 개발 DB와 분리된 `agent_store_integration` DB를 사용합니다. 새 compose volume은 이
DB를 자동 생성합니다. 기존 volume에 DB가 없다면 PostgreSQL 관리자 계정으로 한 번 생성합니다. 테스트 실행 전
`.env`에 `INTEGRATION_DATASOURCE_PASSWORD`를 명시해야 합니다.

```powershell
Set-Location ../agent-store-infra
docker compose --env-file ../agent-store-be/.env exec postgres createdb -U postgres agent_store_integration
```

그 뒤 `RUN_POSTGRES_INTEGRATION_TESTS=true`와 `SPRING_EXCLUSIVE_MAINTENANCE=true`를 모두 명시해야 실제
integration test가 실행됩니다. 일반 server boot용 안전장치가 아니라 파괴적 fixture 정리를 허용하는 test opt-in이며, 테스트 지원 코드도
`agent_store`에 연결되면 실패합니다. 독립 Go demo-agent는 해당 프로젝트에서 `go test ./...`, `go vet ./...`,
`go build ./...`로 확인합니다.

## Function Contract Marketplace

개발자는 `/api/function-contracts`에서 공용 입출력 계약을 만든 뒤 Agent Version에 `functionContractId`를 연결할 수 있습니다. 계약은 생성 후
수정하거나 삭제하지 않으며 변경이 필요하면 새 `contractVersion`을 등록합니다. dependency는 특정 Agent를 직접 지정하거나, function contract와
공급자 범위(`pinned`, `allowlist`, `marketplace`)·선택 전략을 선언합니다.

- `lowest_price`: 가격이 낮은 공급자부터 선택하고 같은 가격이면 최신 Version을 우선합니다.
- `latest_version`: 최신 Version부터 선택하고 같은 Version이면 가격이 낮은 공급자를 우선합니다.
- `highest_reliability`, `fastest`, `balanced`: 30일 실행 관측값을 쓰며, 관측이 부족한 공급자는 명시적인 exploration에서만 선택합니다.

공급자 선택은 Quote 발급 중에만 일어납니다. 선택된 Agent, Version, endpoint, 가격, `payTo`, 계약 Schema와 후보 제외 사유는 snapshot에
고정됩니다. 실행 중 호출 실패, 출력 계약 위반 또는 결제 불명 상태에서 다른 공급자로 자동 전환하거나 다시 결제하지 않습니다.

독립 공급자 reference scenario는 `agent-store-infra` Compose의 demo-agent 서비스로 실행합니다. 같은 Go image가
investment, financial, news-fast, news-deep, risk endpoint를 제공하며 x402 mode에서는 각 slug에 별도의 price와
`payTo`를 사용합니다.

Spring의 공급자 선택부터 developer별 정산까지는 전용 PostgreSQL opt-in E2E로 fresh DB에서 재현할 수 있습니다. fixture는 서로 다른
developer·endpoint·`payTo`를 가진 investment, news-fast, news-deep, risk를 만들고 `lowest_price`로 news-fast를 선택합니다. Quote
snapshot을 만든 뒤 더 저렴한 news-deep Version을 publish해도 기존 실행은 고정된 news-fast만 호출하며, root·선택된 뉴스·risk 개발자에게만
각각 1000·900·1000 atomic 수익이 기록되는지 검증합니다. 생성한 행은 해당 테스트 ID만 추적해 종료 시 제거합니다.

```powershell
$env:RUN_POSTGRES_INTEGRATION_TESTS='true'
$env:SPRING_EXCLUSIVE_MAINTENANCE='true'
.\gradlew.bat test --tests "com.agentstore.execution.PostgresSimulatedRuntimeE2eIntegrationTest.capability reference selects one provider and settles distinct developer revenues"
```

화면을 이용한 수동 시연에서는 Go Compose를 먼저 띄우고 개발자 모드의 `기능 계약`, Agent Version, Dependency 화면에서 같은 계약과
endpoint를 등록합니다. 시연용 실제 x402 지갑을 사용할 때는 Go 프로젝트 `.env`의 공급자별 `payTo`와 Spring에 등록한 Version의
`payTo`가 정확히 일치해야 합니다.

## 11. 문제 해결

- **CORS 403:** 요청 `Origin`이 `application.yaml`의 `agent-store.cors-origins`와 맞는지 확인하고 Spring을 재시작합니다. query string은 origin에 포함되지
  않습니다.
- **Flyway checksum 오류:** 적용된 migration을 임의 수정한 상태입니다. 원본을 복구하거나 새 migration을 추가합니다. 공유 DB에서 성급히
  `repair`하면 drift를 숨길 수 있습니다.
- **relation/column JDBC 오류:** `application.yaml`의 연결 DB/schema, Flyway 적용 상태, Docker host port와 datasource password를 확인합니다.
- **Quote 뒤 실행 거부:** Quote가 5분을 넘겼거나 예산이 정확히 같지 않거나 recovery readiness가 준비되지 않았을 수 있습니다. 새 Quote로 다시
  승인합니다.

## 12. 보안 경계

- 금액은 atomic decimal string입니다.
- endpoint와 dependency는 Quote 시 검증되고 snapshot으로 고정됩니다.
- callback은 서명 token과 idempotency key를 검사합니다.
- 외부 결제 전 durable intent/reservation을 만듭니다.
- `X402_PRIVATE_KEY`는 Spring 배포 secret으로만 주입하고 브라우저, DB, 로그와 Git 바깥에 둡니다.
- `X-Trace-Id`는 추적용이지 인증 수단이 아닙니다.
