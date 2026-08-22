package com.agentstore.common.exception.client

import com.agentstore.common.exception.BusinessException
import com.agentstore.common.exception.constants.ErrorCode

abstract class ClientException : BusinessException {
    protected constructor(errorCode: ErrorCode, vararg messageArguments: Any?) : super(
        errorCode,
        *messageArguments,
    )

    protected constructor(
        errorCode: ErrorCode,
        cause: Throwable,
        vararg messageArguments: Any?,
    ) : super(errorCode, cause, *messageArguments)
}
