package com.agentstore.dependency.model.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProviderSelectionStrategy {
    LOWEST_PRICE("lowest_price"),
    LATEST_VERSION("latest_version"),
    HIGHEST_RELIABILITY("highest_reliability"),
    FASTEST("fastest"),
    BALANCED("balanced");

    private final String value;

    ProviderSelectionStrategy(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ProviderSelectionStrategy from(String value) {
        for (ProviderSelectionStrategy strategy : values()) {
            if (strategy.value.equals(value)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unsupported provider selection strategy");
    }
}
