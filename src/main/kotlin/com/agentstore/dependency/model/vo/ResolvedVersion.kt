package com.agentstore.dependency.model.vo

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.util.UUID

data class ResolvedVersion(
    val id: UUID,
    val agentId: UUID,
    val agentCode: String,
    val agentName: String,
    val agentDescription: String,
    val semver: String,
    val endpoint: String,
    val priceAtomic: BigInteger,
    val network: String,
    val asset: String,
    val payTo: String,
    val responseFormat: AgentResponseFormat = AgentResponseFormat.JSON,
    val functionContract: ResolvedFunctionContract? = null,
)

data class ResolvedFunctionContract(
    val id: UUID,
    val code: String,
    val contractVersion: String,
    val inputSchema: JsonNode,
    val outputSchema: JsonNode,
)
