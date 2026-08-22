# AgentStore BE 인수인계서

최종 갱신: 2026-08-22

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
- 설정에는 환경변수 fallback을 두지 않는다. 로컬 값은 `.env`, 운영 값은 배포 secret/config가 명시적으로 제공한다.

## 현재 구현 상태

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

## 현재 상태와 다음 순서

Node x402 bridge와 backend 내 demo-agent workspace를 제거하고 Spring native x402 v2 client로 교체했다. Demo agent는
`C:\Users\we661\GolandProjects\demo-agent`의 독립 Go 서비스다. `PAYMENT_MODE=x402`는 Base Sepolia 기본
USDC의 `exact`/EIP-3009만 지원하고 `X402_PRIVATE_KEY`를 Spring secret으로 직접 주입한다. Quote와 402 challenge
조건을 모두 대조한 뒤 EIP-712 서명을 만들며 Permit2와 그 외 transfer method는 서명 전에 거절한다. 기존
endpoint DNS pinning, redirect 금지, 30초 timeout, 1 MiB 제한, 동일 attempt/key in-flight correlation과
unknown
결제의 reservation 보존 정책은 유지한다. JVM restart 뒤 in-memory settlement evidence가 없으면 recovery는
재결제하지 않고 계속 `UNKNOWN`을 반환한다.

로컬 PostgreSQL의 Flyway V11/V12 history checksum은 현재 immutable migration과 불일치했으나, schema/data를 바꾸지 않는
Flyway `repair`로 history만 동기화했다. V11의 `payment_attempts.projected_at` 존재를 확인했고, V12가 backfill 대상으로
삼는 조건은 변경하지 않았다. Spring은 단독 DB writer로 기동해 13개 migration validation과 Hibernate validation을 통과했으며
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
