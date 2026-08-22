package com.agentstore.execution.exception

import com.agentstore.common.exception.client.ClientException
import com.agentstore.common.exception.constants.ErrorCode

class ExecutionNotFoundException : ClientException(errorCode = ErrorCode.EXECUTION_NOT_FOUND)
