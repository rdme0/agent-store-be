package com.agentstore.execution.dto.request

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class RuntimeDependencyInvocationRequest(
    @field:NotNull val agentVersionId: UUID?,
    @field:NotNull val callPath: List<String>?,
    val input: JsonNode? = null,
)
