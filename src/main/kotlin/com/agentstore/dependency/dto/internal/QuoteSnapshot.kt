package com.agentstore.dependency.dto.internal

import java.util.UUID

data class ResolvedVersionSnapshot(val id: UUID, val agentId: UUID, val agentSlug: String, val semver: String, val endpoint: String, val priceAtomic: String, val network: String, val asset: String, val payTo: String)
data class DependencySnapshot(val dependencyId: UUID, val targetAgentId: UUID, val targetAgentSlug: String, val versionConstraint: String, val required: Boolean, val maxPriceAtomic: String, val maxCalls: Int, val resolved: QuoteSnapshot? = null)
data class QuoteSnapshot(val version: ResolvedVersionSnapshot, val dependencies: List<DependencySnapshot>)
