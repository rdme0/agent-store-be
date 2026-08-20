package com.agentstore.common.exception.client;

import com.agentstore.common.exception.BusinessException;
import com.agentstore.common.exception.constants.ErrorCode;

public abstract class ClientException extends BusinessException {
    protected ClientException(ErrorCode errorCode, Object... messageArguments) {
        super(errorCode, messageArguments);
    }

    protected ClientException(ErrorCode errorCode, Throwable cause, Object... messageArguments) {
        super(errorCode, cause, messageArguments);
    }
}
