package com.agentstore.common.exception.server

import com.agentstore.common.exception.BusinessException
import com.agentstore.common.exception.constants.ErrorCode

abstract class ServerException : BusinessException {
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
