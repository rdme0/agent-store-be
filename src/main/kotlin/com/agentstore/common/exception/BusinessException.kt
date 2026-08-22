package com.agentstore.common.exception

import com.agentstore.common.exception.constants.ErrorCode
import org.springframework.core.NestedRuntimeException

abstract class BusinessException : NestedRuntimeException {
    val errorCode: ErrorCode
    val messageArguments: Array<out Any?>

    protected constructor(errorCode: ErrorCode, vararg messageArguments: Any?) : super(
        errorCode.formatMessage(*messageArguments),
    ) {
        this.errorCode = errorCode
        this.messageArguments = messageArguments.copyOf()
    }

    protected constructor(
        errorCode: ErrorCode,
        cause: Throwable,
        vararg messageArguments: Any?,
    ) : super(errorCode.formatMessage(*messageArguments), cause) {
        this.errorCode = errorCode
        this.messageArguments = messageArguments.copyOf()
    }
}
