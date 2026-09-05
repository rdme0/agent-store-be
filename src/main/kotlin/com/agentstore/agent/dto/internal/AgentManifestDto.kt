package com.agentstore.agent.dto.internal

import com.agentstore.agent.model.vo.AgentUsageType
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class AgentManifestDto(
    @field:Pattern(regexp = "^agentstore/v1$") val apiVersion: String,
    @field:Valid val agent: ManifestAgentDto,
    @field:Valid val dependencies: List<ManifestDependencyDto> = emptyList(),
)

data class ManifestAgentDto(
    val developerId: UUID,
    @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") val code: String,
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank @field:Size(max = 2000) val description: String,
    @field:NotBlank @field:Size(max = 32) val version: String,
    val usageType: AgentUsageType,
    @field:Valid val function: ManifestFunctionDto,
    @field:NotBlank val endpoint: String,
    @field:Valid val payment: ManifestPaymentDto,
    val verificationInput: JsonNode? = null,
)

data class ManifestFunctionDto(
    @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") val code: String,
    @field:NotBlank @field:Size(max = 32) val version: String,
)

data class ManifestPaymentDto(
    @field:Pattern(regexp = "^[1-9][0-9]*$") val priceAtomic: String,
    @field:NotBlank val network: String,
    @field:NotBlank val asset: String,
    @field:NotBlank val payTo: String,
)

data class ManifestDependencyDto(
    @field:Valid val function: ManifestFunctionDto,
    @field:Valid val providers: ManifestProvidersDto,
    @field:Valid val constraints: ManifestConstraintsDto,
    @field:Valid val resolution: ManifestResolutionDto,
)

data class ManifestProvidersDto(
    val scope: ProviderScope,
    val pinnedAgentCode: String? = null,
    val allowedAgentCodes: List<String> = emptyList(),
)

data class ManifestConstraintsDto(
    @field:NotBlank val versionConstraint: String,
    val required: Boolean,
    @field:Pattern(regexp = "^[1-9][0-9]*$") val maxPriceAtomic: String,
    @field:Min(1) @field:Max(5) val maxCalls: Int,
    @field:Min(0) @field:Max(100) val minReliabilityPercent: Int? = null,
    @field:Min(1) val maxP95LatencyMillis: Int? = null,
)

data class ManifestResolutionDto(
    val strategy: ProviderSelectionStrategy? = null,
)
