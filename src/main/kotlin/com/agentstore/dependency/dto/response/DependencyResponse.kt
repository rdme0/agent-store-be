package com.agentstore.dependency.dto.response

import com.agentstore.dependency.model.entity.AgentDependency
import java.time.Instant
import java.util.UUID

data class DependencyResponse(val id: UUID, val sourceVersionId: UUID, val targetAgentId: UUID, val targetAgentSlug: String, val versionConstraint: String, val required: Boolean, val maxPriceAtomic: String, val maxCalls: Int, val createdAt: Instant, val updatedAt: Instant) {
    companion object {
        fun from(dependency: AgentDependency, targetAgentSlug: String) = DependencyResponse(dependency.id, dependency.sourceVersionId, dependency.targetAgentId, targetAgentSlug, dependency.versionConstraint, dependency.isRequired, dependency.maxPriceAtomic.toString(), dependency.maxCalls, dependency.createdAt, dependency.updatedAt)
    }
}

data class QuoteWarning(val code: String, val dependencyId: UUID, val targetAgentId: UUID, val targetAgentSlug: String, val versionConstraint: String)
data class QuoteResponse(val id: UUID, val rootVersionId: UUID, val expiresAt: Instant, val maxCostAtomic: String, val snapshot: com.agentstore.dependency.dto.internal.QuoteSnapshot, val warnings: List<QuoteWarning>)
