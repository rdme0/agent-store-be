package com.agentstore.dependency.exception;

import com.agentstore.common.exception.client.ClientException;
import com.agentstore.common.exception.constants.ErrorCode;

public final class DependencyCycleDetectedException extends ClientException {
    public DependencyCycleDetectedException(String cyclePath) {
        super(ErrorCode.DEPENDENCY_CYCLE_DETECTED, cyclePath);
    }
}
