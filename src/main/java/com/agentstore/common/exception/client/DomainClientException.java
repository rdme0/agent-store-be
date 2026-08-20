package com.agentstore.common.exception.client;

import com.agentstore.common.exception.constants.ErrorCode;

public class DomainClientException extends ClientException {
    public DomainClientException(ErrorCode errorCode, Object... messageArguments) {
        super(errorCode, messageArguments);
    }

    public DomainClientException(ErrorCode errorCode, Throwable cause, Object... messageArguments) {
        super(errorCode, cause, messageArguments);
    }
}
