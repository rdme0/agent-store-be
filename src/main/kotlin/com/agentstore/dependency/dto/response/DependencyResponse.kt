package com.agentstore.dependency.dto.response

import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.dependency.model.entity.AgentDependency
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class DependencyResponse(
    val id: UUID,
    val sourceVersionId: UUID,
    val targetAgentId: UUID,
    val targetAgentSlug: String,
    val versionConstraint: String,
    val required: Boolean,
    @field:Schema(pattern = "^[0-9]+$") val maxPriceAtomic: String,
    @field:Schema(minimum = "1", maximum = "5") val maxCalls: Int,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(dependency: AgentDependency, targetAgentSlug: String): DependencyResponse {
            return DependencyResponse(
                id = dependency.id,
                sourceVersionId = dependency.sourceVersionId,
                targetAgentId = dependency.targetAgentId,
                targetAgentSlug = targetAgentSlug,
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
    val targetAgentId: UUID,
    val targetAgentSlug: String,
    val versionConstraint: String
)

data class QuoteResponse(
    val id: UUID,
    val rootVersionId: UUID,
    val expiresAt: Instant,
    @field:Schema(pattern = "^[0-9]+$") val maxCostAtomic: String,
    val snapshot: QuoteSnapshotDto,
    val warnings: List<QuoteWarning>
)
