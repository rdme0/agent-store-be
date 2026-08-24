package com.agentstore.agent.dto.request

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateFunctionContractRequest(
    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
    val code: String,
    @field:NotBlank @field:Size(max = 32) val contractVersion: String,
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank @field:Size(max = 2000) val description: String,
    val responseFormat: AgentResponseFormat,
    val inputSchema: JsonNode,
    val outputSchema: JsonNode,
)
