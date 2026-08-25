package com.agentstore.agent.dto.request

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.model.vo.AgentUsageType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class DemoCatalogBootstrapRequest(
    @field:Valid val agents: List<DemoCatalogAgentRequest>,
)

data class DemoCatalogAgentRequest(
    val developerId: UUID,
    @field:NotBlank @field:Size(max = 120) val developerName: String,
    @field:NotBlank @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") val code: String,
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank @field:Size(max = 2000) val description: String,
    @field:Valid val functionContract: DemoFunctionContractRequest,
    @field:NotBlank @field:Size(max = 32) val semver: String,
    @field:NotBlank @field:Size(max = 2048) val endpoint: String,
    @field:Pattern(regexp = "^[0-9]+$") val priceAtomic: String,
    @field:NotBlank val network: String,
    @field:NotBlank val asset: String,
    @field:NotBlank val payTo: String,
    val responseFormat: AgentResponseFormat,
    val usageType: AgentUsageType,
    @field:Valid val dependencies: List<DemoCatalogDependencyRequest> = emptyList(),
)

data class DemoFunctionContractRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") val code: String,
    @field:NotBlank @field:Size(max = 32) val contractVersion: String,
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank @field:Size(max = 2000) val description: String,
    val responseFormat: AgentResponseFormat,
    val inputSchema: Any,
    val outputSchema: Any,
)

data class DemoCatalogDependencyRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") val functionCode: String,
    @field:NotBlank @field:Size(max = 32) val contractVersion: String,
    @field:NotBlank @field:Size(max = 128) val versionConstraint: String,
    @field:Pattern(regexp = "^[0-9]+$") val maxPriceAtomic: String,
)
