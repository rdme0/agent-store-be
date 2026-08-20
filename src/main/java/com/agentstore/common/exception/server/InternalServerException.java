package com.agentstore.common.exception.server;

import com.agentstore.common.exception.constants.ErrorCode;

public class InternalServerException extends ServerException {
    public InternalServerException(Throwable cause) {
        super(ErrorCode.INTERNAL_SERVER_ERROR, cause);
    }
}
