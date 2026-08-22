package com.agentstore.common.exception.server

import com.agentstore.common.exception.constants.ErrorCode

class InternalServerException(cause: Throwable) : ServerException(ErrorCode.INTERNAL_SERVER_ERROR, cause)
