package com.agentstore.external.model.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ExternalInvocationStatus {
    PAYMENT_PENDING("payment_pending"),
    SETTLING("settling"),
    SETTLED("settled"),
    EXECUTION_CREATED("execution_created"),
    RECONCILIATION_REQUIRED("reconciliation_required"),
    FAILED("failed");

    private final String wireValue;

    ExternalInvocationStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String getWireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ExternalInvocationStatus fromWireValue(String wireValue) {
        for (ExternalInvocationStatus status : values()) {
            if (status.wireValue.equals(wireValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("external_invocation_status_invalid");
    }
}
