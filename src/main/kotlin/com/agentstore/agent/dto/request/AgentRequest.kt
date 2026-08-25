package com.agentstore.agent.dto.request

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.model.vo.AgentUsageType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateAgentRequest(
    val developerId: UUID,
    @field:NotBlank @field:Size(max = 80) @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") val code: String,
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank @field:Size(max = 2000) val description: String,
    @field:NotBlank @field:Size(max = 32) val semver: String,
    @field:NotBlank @field:Size(max = 2048) val endpoint: String,
    @field:Pattern(regexp = "^[0-9]+$") val priceAtomic: String,
    @field:NotBlank @field:Size(max = 128) val network: String,
    @field:NotBlank @field:Size(max = 128) val asset: String,
    @field:NotBlank @field:Size(max = 128) val payTo: String,
    val responseFormat: AgentResponseFormat = AgentResponseFormat.JSON,
    val functionContractId: UUID? = null,
    val usageType: AgentUsageType = AgentUsageType.INTERNAL_COMPONENT,
)

data class UpdateAgentRequest(
    @field:Size(min = 1, max = 120) val name: String? = null,
    @field:Size(min = 1, max = 2000) val description: String? = null,
    val usageType: AgentUsageType? = null,
) {
    fun isEmpty(): Boolean {
        return name == null && description == null && usageType == null
    }
}

data class CreateAgentVersionRequest(
    @field:NotBlank @field:Size(max = 32) val semver: String,
    @field:NotBlank @field:Size(max = 2048) val endpoint: String,
    @field:Pattern(regexp = "^[0-9]+$") val priceAtomic: String,
    @field:NotBlank @field:Size(max = 128) val network: String,
    @field:NotBlank @field:Size(max = 128) val asset: String,
    @field:NotBlank @field:Size(max = 128) val payTo: String,
    val responseFormat: AgentResponseFormat = AgentResponseFormat.JSON,
    val functionContractId: UUID? = null,
)
