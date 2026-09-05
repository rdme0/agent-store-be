package com.agentstore.common.exception.constants

import java.util.Locale
import org.springframework.http.HttpStatus

enum class ErrorCode(
    val domain: Domain,
    val status: HttpStatus,
    val number: Int,
    val message: String,
) {
    INVALID_INPUT_VALUE(Domain.COMMON, HttpStatus.BAD_REQUEST, 1, "유효하지 않은 입력 값입니다."),
    DATA_INTEGRITY_CONFLICT(Domain.COMMON, HttpStatus.CONFLICT, 1, "요청이 현재 데이터 상태와 충돌합니다."),
    INTERNAL_SERVER_ERROR(Domain.COMMON, HttpStatus.INTERNAL_SERVER_ERROR, 1, "서버 내부 오류가 발생했습니다."),
    DEMO_AUTH_REQUIRED(Domain.COMMON, HttpStatus.UNAUTHORIZED, 2, "데모 access token이 필요합니다."),
    DEMO_ACCESS_DENIED(Domain.COMMON, HttpStatus.FORBIDDEN, 3, "이 개발자 리소스에 접근할 수 없습니다."),

    AGENT_INVALID_PRICE(Domain.AGENT, HttpStatus.BAD_REQUEST, 1, "Agent price가 올바르지 않습니다."),
    INVALID_ENDPOINT(Domain.AGENT, HttpStatus.BAD_REQUEST, 2, "Agent endpoint가 올바르지 않습니다: %s"),
    INVALID_PAYMENT_TERMS(Domain.AGENT, HttpStatus.BAD_REQUEST, 3, "결제 조건이 올바르지 않습니다."),
    INVALID_SEMVER(Domain.AGENT, HttpStatus.BAD_REQUEST, 4, "semver가 올바르지 않습니다."),
    UNSAFE_AGENT_ENDPOINT(Domain.AGENT, HttpStatus.BAD_REQUEST, 5, "안전하지 않은 Agent endpoint입니다: %s"),
    AGENT_DEVELOPER_NOT_FOUND(Domain.AGENT, HttpStatus.NOT_FOUND, 1, "Agent Developer를 찾을 수 없습니다."),
    AGENT_NOT_FOUND(Domain.AGENT, HttpStatus.NOT_FOUND, 2, "Agent를 찾을 수 없습니다."),
    AGENT_VERSION_NOT_FOUND(Domain.AGENT, HttpStatus.NOT_FOUND, 3, "Agent version을 찾을 수 없습니다."),
    AGENT_ALREADY_EXISTS(Domain.AGENT, HttpStatus.CONFLICT, 1, "이미 존재하는 Agent입니다."),
    AGENT_HAS_VERSIONS(Domain.AGENT, HttpStatus.CONFLICT, 2, "Version이 있는 Agent는 삭제할 수 없습니다."),
    AGENT_VERSION_ALREADY_EXISTS(Domain.AGENT, HttpStatus.CONFLICT, 3, "Agent version이 이미 존재합니다."),
    INVALID_VERSION_TRANSITION(Domain.AGENT, HttpStatus.CONFLICT, 4, "Agent version 상태 전환이 올바르지 않습니다."),
    FUNCTION_CONTRACT_NOT_FOUND(Domain.AGENT, HttpStatus.NOT_FOUND, 4, "Function Contract를 찾을 수 없습니다."),
    FUNCTION_CONTRACT_ALREADY_EXISTS(Domain.AGENT, HttpStatus.CONFLICT, 5, "Function Contract가 이미 존재합니다."),
    INVALID_FUNCTION_CONTRACT_SCHEMA(
        Domain.AGENT,
        HttpStatus.BAD_REQUEST,
        6,
        "Function Contract Schema가 올바르지 않습니다.",
    ),
    FUNCTION_CONTRACT_RESPONSE_FORMAT_MISMATCH(
        Domain.AGENT,
        HttpStatus.CONFLICT,
        6,
        "Function Contract와 응답 형식이 일치하지 않습니다.",
    ),
    PROVIDER_VERIFICATION_REQUIRED(Domain.AGENT, HttpStatus.CONFLICT, 7, "공급자 x402 검증이 필요합니다."),
    PROVIDER_VERIFICATION_IN_PROGRESS(Domain.AGENT, HttpStatus.CONFLICT, 8, "공급자 x402 검증이 진행 중입니다."),
    PROVIDER_NOT_READY(Domain.AGENT, HttpStatus.SERVICE_UNAVAILABLE, 9, "공급자 Agent가 현재 검증되지 않았습니다."),

    DEPENDENCY_INVALID_PRICE(Domain.DEPENDENCY, HttpStatus.BAD_REQUEST, 1, "Dependency maxPriceAtomic이 올바르지 않습니다."),
    INVALID_MAX_CALLS(Domain.DEPENDENCY, HttpStatus.BAD_REQUEST, 2, "maxCalls는 1 이상 5 이하이어야 합니다."),
    INVALID_VERSION_CONSTRAINT(Domain.DEPENDENCY, HttpStatus.BAD_REQUEST, 3, "version constraint가 올바르지 않습니다."),
    DEPENDENCY_NOT_FOUND(Domain.DEPENDENCY, HttpStatus.NOT_FOUND, 1, "Dependency를 찾을 수 없습니다."),
    ACTIVE_VERSION_IMMUTABLE(Domain.DEPENDENCY, HttpStatus.CONFLICT, 1, "ACTIVE version의 Dependency는 변경할 수 없습니다."),
    DEPENDENCY_ALREADY_EXISTS(Domain.DEPENDENCY, HttpStatus.CONFLICT, 2, "Dependency가 이미 존재합니다."),
    DEPENDENCY_CYCLE_DETECTED(Domain.DEPENDENCY, HttpStatus.CONFLICT, 3, "Dependency cycle이 감지되었습니다. 경로: %s"),
    DEPENDENCY_NOT_RESOLVED(Domain.DEPENDENCY, HttpStatus.CONFLICT, 4, "필수 Dependency를 resolve할 수 없습니다."),
    COST_OVERFLOW(Domain.DEPENDENCY, HttpStatus.UNPROCESSABLE_CONTENT, 1, "실행 비용이 허용 범위를 초과했습니다."),
    DEPENDENCY_DEPTH_EXCEEDED(
        Domain.DEPENDENCY,
        HttpStatus.UNPROCESSABLE_CONTENT,
        2,
        "Dependency graph depth가 최대값을 초과했습니다.",
    ),
    DEPENDENCY_PRICE_EXCEEDED(Domain.DEPENDENCY, HttpStatus.UNPROCESSABLE_CONTENT, 3, "Dependency 가격이 허용 상한을 초과했습니다."),
    EXECUTION_STEPS_EXCEEDED(
        Domain.DEPENDENCY,
        HttpStatus.UNPROCESSABLE_CONTENT,
        4,
        "Dependency graph의 실행 step 수가 최대값을 초과했습니다.",
    ),
    PROVIDER_CANDIDATE_LIMIT_EXCEEDED(
        Domain.DEPENDENCY,
        HttpStatus.UNPROCESSABLE_CONTENT,
        5,
        "Function Contract 공급자 후보 수가 허용 범위를 초과했습니다.",
    ),
    PROVIDER_RESOLUTION_LIMIT_EXCEEDED(
        Domain.DEPENDENCY,
        HttpStatus.UNPROCESSABLE_CONTENT,
        6,
        "Function Contract 공급자 조합 탐색 한도를 초과했습니다.",
    ),
    PROVIDER_METRICS_INSUFFICIENT(
        Domain.DEPENDENCY,
        HttpStatus.UNPROCESSABLE_CONTENT,
        7,
        "성능 기반 공급자 선택에 필요한 관측 지표가 부족합니다.",
    ),

    QUOTE_NOT_FOUND(Domain.QUOTE, HttpStatus.NOT_FOUND, 1, "Quote를 찾을 수 없습니다."),
    QUOTE_EXPIRED(Domain.QUOTE, HttpStatus.CONFLICT, 1, "Quote가 만료되었습니다."),

    INVALID_INVOCATION_TOKEN(Domain.EXECUTION, HttpStatus.UNAUTHORIZED, 1, "Invocation token이 올바르지 않습니다."),
    INVOCATION_TOKEN_EXPIRED(Domain.EXECUTION, HttpStatus.UNAUTHORIZED, 2, "Invocation token이 만료되었습니다."),
    INVALID_CALL_PATH(Domain.EXECUTION, HttpStatus.FORBIDDEN, 1, "Invocation callPath가 올바르지 않습니다."),
    UNDECLARED_DEPENDENCY(Domain.EXECUTION, HttpStatus.FORBIDDEN, 2, "선언되지 않은 Dependency입니다."),
    EXECUTION_NOT_FOUND(Domain.EXECUTION, HttpStatus.NOT_FOUND, 1, "Execution을 찾을 수 없습니다."),
    RUNTIME_STEP_NOT_FOUND(Domain.EXECUTION, HttpStatus.NOT_FOUND, 2, "Runtime step을 찾을 수 없습니다."),
    EXECUTION_NOT_ACTIVE(Domain.EXECUTION, HttpStatus.CONFLICT, 1, "Execution이 더 이상 활성 상태가 아닙니다."),
    PARENT_STEP_NOT_ACTIVE(Domain.EXECUTION, HttpStatus.CONFLICT, 3, "Parent step이 더 이상 활성 상태가 아닙니다."),
    INVALID_DEPENDENCY_LIMIT(Domain.EXECUTION, HttpStatus.UNPROCESSABLE_CONTENT, 3, "Dependency 실행 제한이 올바르지 않습니다."),
    IDEMPOTENCY_KEY_REQUIRED(Domain.EXECUTION, HttpStatus.BAD_REQUEST, 1, "Idempotency-Key가 필요합니다."),
    IDEMPOTENCY_IN_PROGRESS(Domain.EXECUTION, HttpStatus.CONFLICT, 2, "동일한 Dependency invocation이 이미 진행 중입니다."),
    BUDGET_MISMATCH(Domain.EXECUTION, HttpStatus.UNPROCESSABLE_CONTENT, 1, "maxBudgetAtomic은 Quote 최대 비용과 같아야 합니다."),
    INVALID_BUDGET(Domain.EXECUTION, HttpStatus.UNPROCESSABLE_CONTENT, 2, "maxBudgetAtomic이 올바르지 않습니다."),
    DEPENDENCY_INVOCATION_FAILED(Domain.EXECUTION, HttpStatus.BAD_GATEWAY, 1, "Dependency Agent 호출에 실패했습니다."),
    AGENT_INPUT_SCHEMA_INVALID(
        Domain.EXECUTION,
        HttpStatus.UNPROCESSABLE_CONTENT,
        4,
        "Agent 입력이 계약 Schema와 일치하지 않습니다.",
    ),
    AGENT_OUTPUT_SCHEMA_INVALID(Domain.EXECUTION, HttpStatus.BAD_GATEWAY, 2, "Agent 출력이 계약 Schema와 일치하지 않습니다."),
    EXECUTION_RECOVERY_IN_PROGRESS(Domain.EXECUTION, HttpStatus.SERVICE_UNAVAILABLE, 1, "Execution 복구가 진행 중입니다."),

    PAYMENT_PRICE_MISMATCH(Domain.PAYMENT, HttpStatus.CONFLICT, 1, "결제 가격 정보가 일치하지 않습니다."),
    FAILED_AFTER_PAYMENT(Domain.PAYMENT, HttpStatus.BAD_GATEWAY, 1, "결제 후 Agent 실행에 실패했습니다."),
    PAYMENT_FAILED(Domain.PAYMENT, HttpStatus.BAD_GATEWAY, 2, "결제에 실패했습니다."),
    PAYMENT_RECONCILIATION_REQUIRED(Domain.PAYMENT, HttpStatus.SERVICE_UNAVAILABLE, 1, "결제 결과 확인이 필요합니다."),

    EXTERNAL_INVOCATION_NOT_FOUND(Domain.EXTERNAL, HttpStatus.NOT_FOUND, 1, "외부 호출을 찾을 수 없습니다."),
    EXTERNAL_IDEMPOTENCY_CONFLICT(Domain.EXTERNAL, HttpStatus.CONFLICT, 1, "동일한 Idempotency-Key의 요청 내용이 다릅니다."),
    EXTERNAL_MAX_TOTAL_EXCEEDED(Domain.EXTERNAL, HttpStatus.UNPROCESSABLE_CONTENT, 1, "호출 최대 비용을 초과했습니다."),
    EXTERNAL_PAYMENT_REQUIRED(Domain.EXTERNAL, HttpStatus.PAYMENT_REQUIRED, 1, "x402 결제가 필요합니다."),
    EXTERNAL_PAYMENT_RECONCILIATION_REQUIRED(
        Domain.EXTERNAL,
        HttpStatus.SERVICE_UNAVAILABLE,
        1,
        "외부 결제 결과 확인이 필요합니다.",
    ),
    EXTERNAL_RATE_LIMITED(Domain.EXTERNAL, HttpStatus.TOO_MANY_REQUESTS, 1, "요청 횟수가 너무 많습니다."),

    INVALID_CURSOR(Domain.REVENUE, HttpStatus.BAD_REQUEST, 1, "cursor가 올바르지 않습니다."),
    DEVELOPER_NOT_FOUND(Domain.REVENUE, HttpStatus.NOT_FOUND, 1, "Developer를 찾을 수 없습니다."),
    ;

    val code: String
        get() {
            return String.format(Locale.ROOT, "%s_%d_%03d", domain.name, status.value(), number)
        }

    @Suppress("NamedArguments")
    fun formatMessage(vararg arguments: Any?): String {
        if (arguments.isEmpty()) {
            return message
        }

        return String.format(Locale.ROOT, message, *arguments)
    }
}
