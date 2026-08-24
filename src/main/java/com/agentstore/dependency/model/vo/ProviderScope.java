package com.agentstore.dependency.model.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProviderScope {
    PINNED("pinned"),
    ALLOWLIST("allowlist"),
    MARKETPLACE("marketplace");

    private final String value;

    ProviderScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ProviderScope from(String value) {
        for (ProviderScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported provider scope");
    }
}
