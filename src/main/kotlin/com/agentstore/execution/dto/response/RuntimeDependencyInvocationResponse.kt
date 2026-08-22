package com.agentstore.execution.dto.response

import java.util.UUID

data class RuntimeDependencyInvocationResponse(
    val stepId: UUID,
    val output: Any?,
    val costAtomic: String
)
