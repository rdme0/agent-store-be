package com.agentstore.agent.model.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AgentUsageType {
    USER_FACING("user_facing"),
    INTERNAL_COMPONENT("internal_component");

    private final String value;

    AgentUsageType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AgentUsageType from(String value) {
        for (AgentUsageType usageType : values()) {
            if (usageType.value.equals(value)) {
                return usageType;
            }
        }
        throw new IllegalArgumentException("Unsupported agent usage type");
    }
}
