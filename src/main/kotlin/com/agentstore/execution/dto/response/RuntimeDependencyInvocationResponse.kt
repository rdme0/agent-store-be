package com.agentstore.execution.dto.response

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

data class RuntimeDependencyInvocationResponse(val stepId: UUID, val output: JsonNode?, val costAtomic: String)
