package com.agentstore.agent.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AgentManifestRequest(
    @field:NotBlank @field:Size(max = 262_144) val content: String,
)
