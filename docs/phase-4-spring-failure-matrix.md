# Spring Phase 4 실행·결제 failure matrix

이번 변경은 `HIGH_RISK`다. 아래 표는 migration 전체 경계를 고정한다. 이번 diff가 건드리지 않은 경계는 `N/A (이번 slice에서 변경 없음)`으로 명시하며, 다음 slice가 시작되기 전 해당 행의 구현 테스트를 추가한다.

| 단계 | 실패·경합 | durable state | 예약·비용 처리 | 재시도·멱등성 | 사용자 결과 | 검증 테스트 |
|---|---|---|---|---|---|---|
| side effect 전 | budget 초과, 중복 runner claim | `Execution` row lock으로 `PENDING → RUNNING`, `PaymentAttempt(REQUIRED)` 생성 | `max - actual - reserved` 범위 안에서만 reserve | `ExecutionRunService.claim`이 두 번째 claim 거절 | 실행 실패 또는 한 번만 진행 | `ExecutionBudgetTest`, `ExecutionRunService` lock integration 예정 |
| request/intent 저장 후 | step 상태 저장 또는 event append 실패 | attempt와 `PAYMENT_REQUIRED`를 저장 | 준비 실패 시 reservation release | attempt id를 이후 settle/reconcile key로 유지 | payment required 또는 failed | `PaymentService` repository integration 예정 |
| external invoke 후 local settlement 전 | agent/payment 응답 후 journal 저장 실패 | journal이 없으면 attempt 실패 처리, simulated slice에서는 재호출 가능 | reservation release | 후속 bridge slice에서 unknown은 reconciliation 보존 | `PAYMENT_FAILED` | deferred payment client test 예정 |
| journal 저장 후 budget 반영 전 | `settle` 이후 row lock/DB 오류 | journal과 transaction identifier 보존, attempt를 reconciliation required로 표시 | reservation을 해제하지 않음 | 재결제하지 않고 reconcile | `PAYMENT_RECONCILIATION_REQUIRED` | journal/recovery integration 예정 |
| restart/reconciliation | 실행 중 process 종료 | active execution/attempt 조회 가능 | 미정산 reservation 보존 | recovery service에서 terminalize | 복구 결과에 따라 실패/재조정 | `PaymentRecoveryStartupService`, recovery integration 예정 |
| callback/terminal race | callback이 terminalization과 경합 | N/A (이번 slice에서 runtime callback 변경 없음) | N/A | 다음 runtime slice에서 claim/terminal atomic update | callback 거절 | N/A — runtime callback diff 없음 |
| duplicate request | 동일 execution POST 또는 runner 중복 | execution id와 claim row lock | reservation 1회 | 두 번째 runner는 no-op | 단일 실행 | claim unit/integration 예정 |
| Flyway baseline | Prisma schema fingerprint 불일치 | startup 중단, baseline 금지 | 쓰기 시작 전 차단 | fingerprint 수정 전 재시도 금지 | readiness 실패 | N/A — migration 파일은 이번 diff에서 변경 없음 |
| SSE replay/live | replay와 live event sequence 경합 | N/A (이번 slice에서 SSE broker 변경 없음) | N/A | 다음 SSE slice에서 sequence dedupe·terminal close | 재연결 가능 | N/A — SSE diff 없음 |
| duplicate settlement | 같은 attempt 재정산 또는 hash 충돌 | journal unique + hash 비교 | 이미 settled면 budget 재청구 금지 | attempt id/idempotency key 전달 | 기존 settlement 유지 또는 reconciliation | `PaymentAttemptTest`, journal integration 예정 |
| revenue settlement | 결제 성공 후 revenue insert 실패 | payment journal 유지, startup recovery가 revenue 재생성 | 비용 정산은 보존 | paymentAttempt unique로 중복 방지 | dashboard eventual consistency | `RevenueSettlementService`, recovery integration 예정 |

현재 자동 게이트: `./gradlew.bat test`, `./gradlew.bat bootJar`, `git diff --check`.
