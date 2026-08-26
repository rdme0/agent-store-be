package com.agentstore.dependency.dto.request

import com.fasterxml.jackson.annotation.JsonIgnore
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateDependencyRequest(
    val targetAgentId: UUID? = null,
    val functionContractId: UUID? = null,
    val providerScope: ProviderScope? = null,
    val selectionStrategy: ProviderSelectionStrategy? = null,
    val allowedProviderAgentIds: Set<UUID>? = null,
    @field:Min(0) @field:Max(100) val minReliabilityPercent: Int? = null,
    @field:Min(1) val maxP95LatencyMillis: Int? = null,
    @field:NotBlank @field:Size(max = 128) val versionConstraint: String,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) val required: Boolean = true,
    @field:Pattern(regexp = "^[0-9]+$") val maxPriceAtomic: String,
    @field:Min(1) @field:Max(5) @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) val maxCalls: Int = 1,
)

data class UpdateDependencyRequest(
    @field:Size(max = 128) val versionConstraint: String? = null,
    val required: Boolean? = null,
    @field:Pattern(regexp = "^[0-9]+$") val maxPriceAtomic: String? = null,
    @field:Min(1) @field:Max(5) val maxCalls: Int? = null,
    val providerScope: ProviderScope? = null,
    val selectionStrategy: ProviderSelectionStrategy? = null,
    val allowedProviderAgentIds: Set<UUID>? = null,
    @field:Min(0) @field:Max(100) val minReliabilityPercent: Int? = null,
    @field:Min(1) val maxP95LatencyMillis: Int? = null,
) {
    @JsonIgnore
    fun isEmpty(): Boolean {
        return versionConstraint == null && required == null && maxPriceAtomic == null && maxCalls == null &&
            providerScope == null && selectionStrategy == null &&
            allowedProviderAgentIds == null && minReliabilityPercent == null &&
            maxP95LatencyMillis == null
    }
}

data class QuoteRequest(@field:Size(max = 128) val versionConstraint: String? = null)
