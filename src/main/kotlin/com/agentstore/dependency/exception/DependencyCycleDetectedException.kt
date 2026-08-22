package com.agentstore.dependency.exception

import com.agentstore.common.exception.client.ClientException
import com.agentstore.common.exception.constants.ErrorCode

class DependencyCycleDetectedException(cyclePath: String) : ClientException(
    ErrorCode.DEPENDENCY_CYCLE_DETECTED,
    cyclePath,
)
