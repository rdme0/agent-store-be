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
