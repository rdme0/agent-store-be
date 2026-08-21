package com.agentstore.dependency.dto.internal

import io.swagger.v3.oas.annotations.media.Schema
import com.agentstore.agent.model.vo.AgentResponseFormat
import java.util.*

data class ResolvedVersionSnapshot(
    val id: UUID,
    val agentId: UUID,
    val agentSlug: String,
    val semver: String,
    @field:Schema(format = "uri") val endpoint: String,
    @field:Schema(pattern = "^[0-9]+$") val priceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    @field:Schema(allowableValues = ["TEXT", "MARKDOWN", "STRUCTURED", "JSON"])
    val responseFormat: AgentResponseFormat,
)

data class DependencySnapshot(
    val dependencyId: UUID,
    val targetAgentId: UUID,
    val targetAgentSlug: String,
    val versionConstraint: String,
    val required: Boolean,
    @field:Schema(pattern = "^[0-9]+$") val maxPriceAtomic: String,
    @field:Schema(minimum = "1", maximum = "5") val maxCalls: Int,
    @field:Schema(nullable = false) val resolved: QuoteSnapshot? = null
)

data class QuoteSnapshot(val version: ResolvedVersionSnapshot, val dependencies: List<DependencySnapshot>)
