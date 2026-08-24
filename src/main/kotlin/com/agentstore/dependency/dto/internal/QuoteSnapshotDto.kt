package com.agentstore.dependency.dto.internal

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.payment.dto.internal.KrwEstimateDto
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class ResolvedVersionSnapshotDto(
    val id: UUID,
    val agentId: UUID,
    val agentSlug: String,
    @field:Schema(nullable = true) val agentName: String? = null,
    @field:Schema(nullable = true) val agentDescription: String? = null,
    val semver: String,
    @field:Schema(format = "uri") val endpoint: String,
    @field:Schema(pattern = "^[0-9]+$") val priceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    @field:Schema(allowableValues = ["TEXT", "MARKDOWN", "STRUCTURED", "JSON"])
    val responseFormat: AgentResponseFormat = AgentResponseFormat.JSON,
    @field:Schema(nullable = true) val functionContract: FunctionContractSnapshotDto? = null,
)

data class FunctionContractSnapshotDto(
    val id: UUID,
    val key: String,
    val contractVersion: String,
    @field:Schema(implementation = JsonNode::class) val inputSchema: JsonNode,
    @field:Schema(implementation = JsonNode::class) val outputSchema: JsonNode,
)

data class ProviderCandidateSnapshotDto(
    val agentId: UUID,
    val agentSlug: String,
    val versionId: UUID,
    val semver: String,
    @field:Schema(pattern = "^[0-9]+$") val priceAtomic: String,
    val status: String,
    @field:Schema(nullable = true) val observationCount: Int? = null,
    @field:Schema(nullable = true) val reliabilityPercent: Int? = null,
    @field:Schema(nullable = true) val p95LatencyMillis: Long? = null,
    @field:Schema(nullable = true) val contractCompliancePercent: Int? = null,
)

data class ProviderSelectionSnapshotDto(
    @field:Schema(nullable = true) val strategy: ProviderSelectionStrategy? = null,
    @field:Schema(nullable = true) val providerScope: ProviderScope? = null,
    val functionContractId: UUID,
    val functionCode: String,
    val functionContractVersion: String,
    val candidates: List<ProviderCandidateSnapshotDto>,
    @field:Schema(nullable = true) val selectedVersionId: UUID? = null,
    @field:Schema(nullable = true) val selectedReason: String? = null,
    val explorationSelected: Boolean = false,
    @field:Schema(nullable = true) val selectionSeedDigest: String? = null,
)

data class DependencySnapshotDto(
    val dependencyId: UUID,
    @field:Schema(nullable = true) val targetAgentId: UUID? = null,
    @field:Schema(nullable = true) val targetAgentSlug: String? = null,
    @field:Schema(nullable = true) val selection: ProviderSelectionSnapshotDto? = null,
    val versionConstraint: String,
    val required: Boolean,
    @field:Schema(pattern = "^[0-9]+$") val maxPriceAtomic: String,
    @field:Schema(minimum = "1", maximum = "5") val maxCalls: Int,
    @field:Schema(nullable = true) val resolved: QuoteSnapshotDto? = null,
)

data class QuoteSnapshotDto(
    val version: ResolvedVersionSnapshotDto,
    val dependencies: List<DependencySnapshotDto>,
    @field:Schema(nullable = true) val krwEstimate: KrwEstimateDto? = null,
)
