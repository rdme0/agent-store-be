package com.agentstore.dependency.dto.response

import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.payment.dto.response.KrwEstimateResponse
import com.agentstore.dependency.model.entity.AgentDependency
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class DependencyResponse(
    val id: UUID,
    val sourceVersionId: UUID,
    @field:Schema(nullable = true) val targetAgentId: UUID? = null,
    @field:Schema(nullable = true) val targetAgentSlug: String? = null,
    @field:Schema(nullable = true) val functionContractId: UUID? = null,
    @field:Schema(nullable = true) val functionCode: String? = null,
    @field:Schema(nullable = true) val functionContractVersion: String? = null,
    @field:Schema(nullable = true) val providerScope: ProviderScope? = null,
    @field:Schema(nullable = true) val selectionStrategy: ProviderSelectionStrategy? = null,
    @field:Schema(nullable = true) val minReliabilityPercent: Int? = null,
    @field:Schema(nullable = true) val maxP95LatencyMillis: Int? = null,
    @field:Schema(nullable = true) val explorationPercent: Int? = null,
    @field:Schema(nullable = true) val reliabilityWeight: Int? = null,
    @field:Schema(nullable = true) val priceWeight: Int? = null,
    @field:Schema(nullable = true) val speedWeight: Int? = null,
    val versionConstraint: String,
    val required: Boolean,
    @field:Schema(pattern = "^[0-9]+$") val maxPriceAtomic: String,
    @field:Schema(minimum = "1", maximum = "5") val maxCalls: Int,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(
            dependency: AgentDependency,
            targetAgentSlug: String?,
            functionCode: String?,
            functionContractVersion: String?,
        ): DependencyResponse {
            return DependencyResponse(
                id = dependency.id,
                sourceVersionId = dependency.sourceVersionId,
                targetAgentId = dependency.targetAgentId,
                targetAgentSlug = targetAgentSlug,
                functionContractId = dependency.functionContractId,
                functionCode = functionCode,
                functionContractVersion = functionContractVersion,
                providerScope = dependency.providerScope,
                selectionStrategy = dependency.selectionStrategy,
                minReliabilityPercent = dependency.minReliabilityPercent,
                maxP95LatencyMillis = dependency.maxP95LatencyMillis,
                explorationPercent = dependency.explorationPercent,
                reliabilityWeight = dependency.reliabilityWeight,
                priceWeight = dependency.priceWeight,
                speedWeight = dependency.speedWeight,
                versionConstraint = dependency.versionConstraint,
                required = dependency.isRequired,
                maxPriceAtomic = dependency.maxPriceAtomic.toString(),
                maxCalls = dependency.maxCalls,
                createdAt = dependency.createdAt,
                updatedAt = dependency.updatedAt,
            )
        }
    }
}

data class QuoteWarning(
    val code: String,
    val dependencyId: UUID,
    val targetAgentId: UUID? = null,
    val targetAgentSlug: String? = null,
    val functionContractId: UUID? = null,
    val functionCode: String? = null,
    val versionConstraint: String
)

data class QuoteResponse(
    val id: UUID,
    val rootVersionId: UUID,
    val expiresAt: Instant,
    @field:Schema(pattern = "^[0-9]+$") val maxCostAtomic: String,
    @field:Schema(nullable = false) val maxCostKrwEstimate: KrwEstimateResponse? = null,
    val snapshot: QuoteSnapshotDto,
    val warnings: List<QuoteWarning>
)
