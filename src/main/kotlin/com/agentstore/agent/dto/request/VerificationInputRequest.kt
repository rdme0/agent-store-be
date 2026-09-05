package com.agentstore.agent.dto.request

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotNull

data class VerificationInputRequest(
    @field:NotNull val verificationInput: JsonNode,
)
