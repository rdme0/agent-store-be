package com.agentstore.dependency.model.vo

import com.agentstore.dependency.model.entity.AgentDependency
import java.math.BigInteger
import java.util.UUID

data class ResolvedEdge(
    val dependency: AgentDependency,
    val targetAgentCode: String?,
    val resolved: ResolvedNode?,
    val selection: ProviderSelection? = null,
)

data class ProviderSelection(
    val strategy: ProviderSelectionStrategy? = null,
    val providerScope: ProviderScope? = null,
    val functionContractId: UUID,
    val functionCode: String,
    val functionContractVersion: String,
    val candidates: List<ProviderCandidate>,
    val selectedVersionId: UUID?,
    val selectedReason: String?,
    val explorationSelected: Boolean = false,
    val selectionSeedDigest: String? = null,
)

data class ProviderCandidate(
    val agentId: UUID,
    val agentCode: String,
    val versionId: UUID,
    val semver: String,
    val priceAtomic: BigInteger,
    val status: String,
    val observationCount: Int? = null,
    val reliabilityPercent: Int? = null,
    val p95LatencyMillis: Long? = null,
    val contractCompliancePercent: Int? = null,
)
