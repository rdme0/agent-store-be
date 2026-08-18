package com.agentstore.dependency.dto

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.dependency.model.entity.AgentDependency
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateDependencyRequest(
    val targetAgentId: UUID,
    @field:NotBlank @field:Size(max = 128) val versionConstraint: String,
    val required: Boolean = true,
    @field:Pattern(regexp = "^[0-9]+$") val maxPriceAtomic: String,
    @field:Min(1) @field:Max(5) val maxCalls: Int = 1,
)

data class UpdateDependencyRequest(
    @field:Size(max = 128) val versionConstraint: String? = null,
    val required: Boolean? = null,
    @field:Pattern(regexp = "^[0-9]+$") val maxPriceAtomic: String? = null,
    @field:Min(1) @field:Max(5) val maxCalls: Int? = null,
) {
    fun isEmpty() = versionConstraint == null && required == null && maxPriceAtomic == null && maxCalls == null
}

data class DependencyResponse(
    val id: UUID,
    val sourceVersionId: UUID,
    val targetAgentId: UUID,
    val targetAgentSlug: String,
    val versionConstraint: String,
    val required: Boolean,
    val maxPriceAtomic: String,
    val maxCalls: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(dependency: AgentDependency) = DependencyResponse(
            id = dependency.id,
            sourceVersionId = dependency.sourceVersion.id,
            targetAgentId = dependency.targetAgent.id,
            targetAgentSlug = dependency.targetAgent.slug,
            versionConstraint = dependency.versionConstraint,
            required = dependency.isRequired,
            maxPriceAtomic = dependency.maxPriceAtomic.toString(),
            maxCalls = dependency.maxCalls,
            createdAt = dependency.createdAt,
            updatedAt = dependency.updatedAt,
        )
    }
}

data class QuoteRequest(
    @field:Size(max = 128) val versionConstraint: String? = null,
)

data class ResolvedVersionSnapshot(
    val id: UUID,
    val agentId: UUID,
    val agentSlug: String,
    val semver: String,
    val endpoint: String,
    val priceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
)

data class DependencySnapshot(
    val dependencyId: UUID,
    val targetAgentId: UUID,
    val targetAgentSlug: String,
    val versionConstraint: String,
    val required: Boolean,
    val maxPriceAtomic: String,
    val maxCalls: Int,
    val resolved: QuoteSnapshot? = null,
)

data class QuoteSnapshot(
    val version: ResolvedVersionSnapshot,
    val dependencies: List<DependencySnapshot>,
)

data class QuoteWarning(
    val code: String,
    val dependencyId: UUID,
    val targetAgentId: UUID,
    val targetAgentSlug: String,
    val versionConstraint: String,
)

data class QuoteResponse(
    val id: UUID,
    val rootVersionId: UUID,
    val expiresAt: Instant,
    val maxCostAtomic: String,
    val snapshot: QuoteSnapshot,
    val warnings: List<QuoteWarning>,
)
