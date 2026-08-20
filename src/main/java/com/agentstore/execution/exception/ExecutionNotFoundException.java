package com.agentstore.execution.exception;

import com.agentstore.common.exception.client.ClientException;
import com.agentstore.common.exception.constants.ErrorCode;

public final class ExecutionNotFoundException extends ClientException {
    public ExecutionNotFoundException() {
        super(ErrorCode.EXECUTION_NOT_FOUND);
    }
}
