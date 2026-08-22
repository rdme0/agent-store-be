package com.agentstore.dependency.model.vo

import com.agentstore.agent.model.vo.AgentResponseFormat
import java.math.BigInteger
import java.util.UUID

data class ResolvedVersion(
    val id: UUID,
    val agentId: UUID,
    val agentSlug: String,
    val semver: String,
    val endpoint: String,
    val priceAtomic: BigInteger,
    val network: String,
    val asset: String,
    val payTo: String,
    val responseFormat: AgentResponseFormat = AgentResponseFormat.JSON,
)
