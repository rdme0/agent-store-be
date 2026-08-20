package com.agentstore.common.exception.server;

import com.agentstore.common.exception.BusinessException;
import com.agentstore.common.exception.constants.ErrorCode;

public abstract class ServerException extends BusinessException {
    protected ServerException(ErrorCode errorCode, Object... messageArguments) {
        super(errorCode, messageArguments);
    }

    protected ServerException(ErrorCode errorCode, Throwable cause, Object... messageArguments) {
        super(errorCode, cause, messageArguments);
    }
}
