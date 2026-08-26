# AgentStore Roadmap

## 제품 정의

AgentStore는 AI Agent가 예산 안에서 다른 전문 Agent를 호출하고, x402로 결제·정산하며, 그 실행 관계를 dependency graph로 관리하는
범용 Agent 거래 플랫폼이다.

기존 카드·구독 기반 API 결제는 사람이 공급자마다 가입하고 결제 수단과 API key를 준비해야 한다. AgentStore는 이 사람 중심 절차를
Agent가 이해할 수 있는 가격·계약·결제 프로토콜로 바꾸는 것을 목표로 한다.

사용자 또는 상위 시스템이 전체 실행과 최대 예산을 한 번 승인하면, 하위 Agent 선택·호출·결제·정산은 정책과 고정된 quote 범위 안에서
자동으로 진행되어야 한다.

```text
사용자 또는 상위 시스템
        │ 전체 실행과 최대 예산 승인
        ▼
상위 Agent
   ├─ x402 결제 → 전문 Agent A
   ├─ x402 결제 → 전문 Agent B
   └─ x402 결제 → 전문 Agent C
        │
        ▼
결과 조합 + 공급자별 정산 + 실행 증명
```

투자·쇼핑·여행은 이 플랫폼의 reference scenario다. 각 Root Agent가 세 전문 Agent의 서비스를 구매하고 결과를 조합하는 과정으로
Agent 간 거래를 증명한다. 특정 도메인 서비스에 플랫폼의 구조와 계약을 종속시키지 않는다.

## 제품 원칙

- x402는 부가 기능이 아니라 Agent 간 machine-to-machine 결제의 핵심 실행 경계다.
- dependency graph는 호출 순서뿐 아니라 계약, 예산, 결제와 공급자 수익의 흐름을 나타낸다.
- 일반 사용자는 기술 용어 없이 전체 예상 비용과 결과를 이해할 수 있어야 한다.
- 개발자는 선택된 Agent, Version, 가격, 결제와 실패 상태를 추적할 수 있어야 한다.
- 자동화는 무제한 자율 실행을 의미하지 않는다. quote로 고정된 공급자·계약·최대 예산과 호출 한도 안에서만 실행한다.
- 결제가 불명확하거나 결제 후 호출이 실패한 상태를 성공이나 미결제로 추정하지 않는다.
- reference scenario의 편의를 위해 범용 계약과 안전 경계를 약화하지 않는다.

## 현재 기반

현재 구현은 다음 기반을 제공한다. 세부 상태와 검증 결과는 [`HANDOFF.md`](./HANDOFF.md)를 기준으로 확인한다.

- Agent와 immutable ACTIVE Version 등록
- 명시적인 Agent dependency와 DAG quote 계산
- Version, endpoint, 가격, 결제 조건과 호출 한도가 고정된 quote snapshot
- Base Sepolia USDC x402 v2 `exact` EIP-3009 결제
- Agent별 payment journal, reservation, settlement와 revenue projection
- 중복 실행 방지, unknown 결제 reconciliation과 recovery
- runtime callback 인증, SSE 실행 관찰과 output format 검증
- 쉬운 사용 모드와 개발자 모드
- 투자·쇼핑·여행 catalog와 단일 Go demo-agent
- immutable function contract와 Version별 계약 구현 선언
- function contract dependency의 `lowest_price`·`latest_version` Quote-time 공급자 선택
- 선택 후보·제외 사유·선택 이유와 입출력 Schema를 고정하는 quote snapshot
- function contract 입력의 결제 전 검증과 출력의 결제 후 검증

현재 dependency는 특정 Agent를 지정하는 direct 방식과 function contract로 공급자를 찾는 방식이 공존한다. function contract 선택은 Quote 시점에만
수행하며 실행 중 fallback, 평판 기반 선택과 mainnet 지원은 아직 제공하지 않는다.

## Phase 1 — 독립 Agent 거래 실증

목표는 한 프로그램 내부의 함수 호출이 아니라 서로 독립적인 공급자 간 거래임을 증명하는 것이다.

- reference scenario의 Agent를 서로 다른 developer와 `payTo`로 등록한다.
- 최소 두 개의 독립 실행 endpoint 또는 프로세스로 공급자 경계를 보여준다.
- Agent별 가격과 실제 정산 수익을 실행 graph에서 확인할 수 있게 한다.
- funded Base Sepolia 환경의 실제 x402 실행을 opt-in smoke로 검증한다.
- 실행 전 최대 비용, Agent별 실제 비용, 결제 상태와 공급자 수익을 한 화면에서 설명한다.

완료 기준:

- 하나의 root 실행이 둘 이상의 독립 `payTo`로 정산된다.
- 같은 실행의 quote, dependency step, payment journal과 revenue 합계가 일치한다.
- 실제 x402 성공 증거와 payment journal·reconciliation 재현 경로가 준비된다.

## Phase 2 — 교체 가능한 Agent 계약

상태: 핵심 계약·Schema 검증·공급자 조회와 YAML manifest import/export 구현 완료. 외부 SDK 표준화는 후속 작업이다.

목표는 특정 Agent ID에 결합하지 않고 같은 역할의 공급자를 안전하게 교체할 수 있는 계약을 만드는 것이다.

- Agent Version에 machine-readable function contract를 선언한다.
- Version에 입력과 출력 JSON Schema를 선언한다.
- publish 시 Schema 자체와 response format의 일관성을 검증한다.
- invocation 입력과 dependency 결과를 선언된 Schema로 검증한다.
- 상위 Agent가 요구하는 계약과 후보 Version의 계약 호환성을 검사한다.
- function contract, Version, Schema, 가격과 x402 조건을 조회할 수 있는 Agent용 manifest를 제공한다.

첫 reference function contract 예시:

```text
finance.stock-financial-analysis
finance.stock-news-analysis
finance.stock-risk-analysis
```

완료 기준:

- 같은 function contract와 호환 Schema를 제공하는 두 Agent를 코드 변경 없이 교체할 수 있다.
- 계약이 맞지 않는 Agent는 quote 또는 invocation 전에 거절된다.
- quote snapshot이 선택된 function contract, Agent, Version과 Schema 계약을 보존한다.

## Phase 3 — 조건 기반 공급자 선택

상태: 가격·Version·execution observation 기반의 결정적 선택과 Quote snapshot 구현 완료. `pinned`·`allowlist`·`marketplace` 범위와 `highest_reliability`·`fastest`·`balanced` 전략을 제공한다. 실행 중 fallback은 후속 작업이다.

목표는 상위 Agent가 특정 공급자를 미리 알지 않아도 필요한 일을 조건으로 요청하는 것이다.

- dependency가 target Agent 대신 function contract와 선택 조건을 선언할 수 있게 한다.
- 최대 가격, Version 범위, 응답 계약, timeout과 최소 신뢰 지표로 후보를 제한한다.
- 결정적인 선택 정책으로 후보 Agent와 Version을 선택한다.
- 선택 후보, 제외 이유와 최종 선택 이유를 quote에서 확인할 수 있게 한다.
- quote 발급 후에는 선택된 공급자, Version, endpoint와 가격을 변경하지 않는다.

최소 시연:

```text
Investment Agent
  → "2원 이하의 주식 뉴스 분석 Agent 필요"
  → 조건에 맞는 공급자 검색
  → 가격과 신뢰 지표로 한 공급자 선택
  → quote 고정
  → x402 결제 후 호출
```

완료 기준:

- 같은 function contract 공급자 둘 이상 중 정책에 맞는 공급자가 자동 선택된다.
- 같은 입력과 registry 상태에서는 같은 공급자가 선택된다.
- quote 이후 가격이나 Version이 변경되어도 기존 실행 계약은 바뀌지 않는다.

## Phase 4 — 제한된 fallback과 예산 위임

목표는 공급자 장애가 전체 실행 실패로 바로 이어지지 않도록 하면서 중복 결제와 예산 초과를 막는 것이다.

- fallback 허용 여부와 후보 수를 dependency 계약에 명시한다.
- 결제 전에 발생한 definite failure만 안전한 다른 공급자로 전환한다.
- 결제 후 실패나 결제 결과 불명 상태는 자동 fallback하지 않고 기존 reconciliation 정책을 따른다.
- fallback을 포함한 전체 최대 비용을 quote에 미리 반영한다.
- root와 각 dependency의 지출 한도, 남은 예산과 호출 횟수를 추적한다.
- 같은 attempt와 idempotency key가 다른 공급자에게 중복 결제되지 않게 한다.

완료 기준:

- 결제 전 timeout 또는 definite failure에서만 예산 안의 대체 공급자를 사용한다.
- 서명 또는 결제 후 결과가 불명확하면 새로운 공급자에게 재결제하지 않는다.
- 모든 fallback 경로에서 reservation, journal, actual cost와 revenue 불변식이 유지된다.

이 Phase는 payment와 recovery 상태 머신을 변경하므로 반드시 `HIGH_RISK` 절차와 failure matrix를 적용한다.

## Phase 5 — 신뢰도와 실행 증명

목표는 Agent 선택과 거래 결과를 운영 데이터로 검증할 수 있게 하는 것이다.

- 30일 execution-step observation으로 Wilson lower-bound 신뢰도, 성공 호출 p95 응답 시간과 output contract compliance를 집계한다. 20건 미만 provider는 명시적인 exploration 없이 metric strategy로 선택하지 않는다.
- 결제 불명 및 reconciliation 발생률을 별도로 표시한다.
- 조작 가능한 사용자 별점보다 검증된 실행 journal 기반 지표를 우선한다.
- 실행별 graph, Version, 가격, 실제 비용, settlement와 시각을 담은 Execution Proof를 제공한다.
- 원본 secret, invocation token, private key, EIP-712 payload, signature와 raw payment header는 Proof에 포함하지 않는다.
- 입력과 출력 원문 공개가 부적절한 경우 hash와 검증 메타데이터만 제공한다.

완료 기준:

- 공급자 선택에 사용한 지표와 선택 당시 값을 quote에서 재현할 수 있다.
- Execution Proof의 합계가 payment journal과 revenue projection에 일치한다.
- 과거 실행은 이후 Agent 지표 변경의 영향을 받지 않는다.

## Phase 6 — 외부 생태계와 운영 확장

상태: 외부 호출의 첫 슬라이스를 구현했다. 외부 개발자는 기본 공개 API에서 계정·영구 API key 없이 direct Agent 또는
Function Contract를 선택해 x402 v2 결제로 호출할 수 있다. 호출 intent와 receipt bearer token, 총액과 플랫폼 수수료, incoming
settlement, 내부 Execution 연결과 SSE 조회를 제공한다. SDK는 아직 제공하지 않으며 HTTP/OpenAPI가 계약의 원본이다.

- 외부 개발자가 manifest와 HTTP/OpenAPI만으로 Agent를 등록하고 호출할 수 있게 한다.
- Agent 공급자 인증, key rotation, webhook과 운영 알림을 제공한다.
- function contract 검색, 공급자 대시보드와 정산 내역 내보내기를 제공한다.
- 실제 사용 데이터로 가격, 성공률, 지연 시간과 사용자 만족도를 평가한다.
- 새로운 network나 payment scheme은 기존 Base Sepolia EIP-3009 경계를 충분히 검증한 뒤 별도 설계한다.

## 시연 시나리오

대표 시연은 다음 질문에 순서대로 답해야 한다.

1. 상위 Agent가 어떤 전문 기능을 필요로 했는가?
2. Marketplace에 어떤 공급자 후보가 있었는가?
3. 왜 해당 Agent와 Version을 선택했는가?
4. 전체 최대 비용은 얼마였고 누가 승인했는가?
5. 각 Agent는 누구에게 얼마를 결제하고 어떤 결과를 받았는가?
6. 실패하거나 결제가 불명확할 때 어떻게 중복 결제를 막았는가?
7. 각 공급자에게 실제로 얼마의 수익이 기록됐는가?

일반 사용자 화면은 쉬운 비용과 결과를 먼저 보여준다. 개발자 화면과 발표 자료는 dependency graph, x402 settlement, failure recovery와
공급자별 revenue를 플랫폼의 핵심 증거로 보여준다.

## 평가 지표

- 사람의 추가 승인 없이 자동으로 완료된 dependency 호출 비율
- quote 최대 비용 대비 실제 비용과 예산 초과 건수
- 중복 결제와 잘못된 revenue projection 건수
- payment settled, reconciliation required와 terminal execution의 일관성
- 공급자 자동 선택 성공률과 계약 호환성 거절 정확도
- 단일 Agent 대비 multi-Agent 결과 품질, 지연 시간과 비용
- 독립 공급자 수, function contract별 후보 수와 실제 거래 수

## 당분간 하지 않는 범위

- 여러 blockchain과 token을 동시에 지원하는 것
- Permit2, ERC-7710과 새로운 x402 payment method
- 무제한 재귀 실행과 quote 밖의 자율 지출
- 검증되지 않은 동적 코드 또는 플러그인 실행
- 실시간 경매와 복잡한 토큰 이코노미
- 투자 reference scenario를 위해 플랫폼 계약을 특정 종목이나 데이터 공급자에 종속시키는 것

새 기능을 제안하거나 구현할 때는 어느 Phase를 진전시키는지, 기존 불변식에 어떤 영향을 주는지, 현재 우선순위보다 먼저 필요한 이유를
명시한다.
