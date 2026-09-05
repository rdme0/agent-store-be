package com.agentstore.common.security.exception

import com.agentstore.common.exception.constants.ErrorCode
import org.springframework.security.core.AuthenticationException

class AgentStoreAuthenticationException(
    val errorCode: ErrorCode,
) : AuthenticationException(errorCode.message) {
    constructor(errorCode: ErrorCode, cause: Throwable) : this(errorCode) {
        initCause(cause)
    }
}
