package com.agentstore.common.exception.client

import com.agentstore.common.exception.constants.ErrorCode

class DomainClientException : ClientException {
    constructor(errorCode: ErrorCode, vararg messageArguments: Any?) : super(
        errorCode,
        *messageArguments,
    )

    constructor(
        errorCode: ErrorCode,
        cause: Throwable,
        vararg messageArguments: Any?,
    ) : super(errorCode, cause, *messageArguments)
}
