# Function Contract 공급자 선택 Failure Matrix

## 범위와 불변식

- `function contract`는 immutable 입출력 약속이며, Agent Version은 하나의 계약 구현을 선언할 수 있다.
- dependency는 특정 Agent를 직접 호출하거나 function contract를 기준으로 `pinned`·`allowlist`·`marketplace` 범위를 선언한다.
- `lowest_price`·`latest_version`·`highest_reliability`·`fastest` 선택은 Quote 생성 시 한 번만 수행한다.
- Quote snapshot은 계약, 후보, 선택 이유, Agent·Version·가격을 고정한다. 실행 중 fallback·재결제는 하지 않는다.
- 직접 호출 dependency와 function dependency는 하나의 명확한 모델만 사용한다. 구형 capability target/policy 열과 API는 존재하지 않는다.
- 결제·reservation·journal·reconciliation과 SSE 상태 머신은 이 변경으로 달라지지 않는다.

| ID | 실패/경계 | 기대 결과 | 금지 결과 | 검증 |
|---|---|---|---|---|
| FC-01 | schema 크기·깊이·원격 `$ref`·응답 형식 불일치 | 계약/Version publish 거절 | 원격 조회, 불일치 ACTIVE Version | `FunctionContractServiceTest`, `AgentServiceFunctionContractTest`, `AgentManifestServiceTest` |
| DEP-01 | direct target 누락 또는 불완전한 function provider rule | 요청과 DB constraint가 거절 | 모호한 dependency 저장 | `DependencyServiceFunctionContractTest`, `PostgresSchemaIntegrationTest` |
| DEP-02 | function 후보 0개 | required quote 거절 | 임의 provider 선택 | `DependencyResolverFunctionContractTest` |
| DEP-03 | 후보 51개 이상 | quote 거절 | 조용한 후보 절단 | `DependencyResolverFunctionContractTest` |
| DEP-04 | metric strategy의 low-sample provider | quote 거절 | 근거 없는 reliability/latency 선택 | `DependencyResolverFunctionContractTest` |
| DEP-05 | allowlist 밖 provider | 후보에서 제외 | 선언 밖 호출·결제 | `DependencyResolverFunctionContractTest` |
| DEP-06 | Quote 뒤 registry/가격 변경 | snapshot 대상만 실행 | 최신 provider로 교체 | `PostgresRuntimeE2eIntegrationTest` |
| RUN-01 | input schema 불일치 | reservation/payment 전 거절 | 외부 호출 또는 결제 | `ExecutionFunctionContractSchemaTest` |
| RUN-02 | output schema 불일치 | settlement 증거 보존 후 invocation failure | 자동 fallback·재결제 | `PostgresRuntimeE2eIntegrationTest` |
| PAY-01 | 결제 후 timeout/unknown | reconciliation required | 다른 provider 재결제 | `X402PaymentServiceTest`, `PaymentRecoveryStartupServiceTest`, `PostgresSettlementRecoveryIntegrationTest` |
| SSE-01 | replay/live 순서 경합 | terminal 상태 보존 | 과거 이벤트가 성공/실패 덮어씀 | `PostgresSseIntegrationTest` |

## API와 UI 경계

- 공개 계약은 `/api/function-contracts`와 `functionContractId`만 사용한다.
- 개발자 UI는 계약/범위/전략/후보 근거를 표시한다. 쉬운 사용 UI는 선택된 분석 단계와 비용만 표시한다.
- generated frontend client는 Spring `/openapi.json`에서만 생성한다. API adapter나 화면에 구형 field fallback을 두지 않는다.
