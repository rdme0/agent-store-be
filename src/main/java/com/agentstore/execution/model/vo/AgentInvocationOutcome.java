package com.agentstore.execution.model.vo;

public enum AgentInvocationOutcome {
    SUCCESS,
    AGENT_HTTP_FAILURE,
    OUTPUT_FORMAT_INVALID,
    OUTPUT_SCHEMA_INVALID,
    PAYMENT_FAILURE,
    PAYMENT_RECONCILIATION_REQUIRED,
    PLATFORM_FAILURE
}
