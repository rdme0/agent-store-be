package com.agentstore.execution.dto.request

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class RuntimeDependencyInvocationRequest(
    @field:NotNull val agentVersionId: UUID?,
    @field:NotNull val callPath: List<String>?,
    val input: Any? = null,
)
