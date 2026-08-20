package com.agentstore.common.exception;

import com.agentstore.common.exception.constants.ErrorCode;
import lombok.Getter;
import org.springframework.core.NestedRuntimeException;

@Getter
public abstract class BusinessException extends NestedRuntimeException {
    private final ErrorCode errorCode;
    private final Object[] messageArguments;

    protected BusinessException(ErrorCode errorCode, Object... messageArguments) {
        super(errorCode.formatMessage(messageArguments));
        this.errorCode = errorCode;
        this.messageArguments = messageArguments == null ? new Object[0] : messageArguments.clone();
    }

    protected BusinessException(ErrorCode errorCode, Throwable cause, Object... messageArguments) {
        super(errorCode.formatMessage(messageArguments), cause);
        this.errorCode = errorCode;
        this.messageArguments = messageArguments == null ? new Object[0] : messageArguments.clone();
    }
}
