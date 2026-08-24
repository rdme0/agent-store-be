package com.agentstore.agent.dto.response

import com.agentstore.agent.model.entity.AgentCapability
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class FunctionContractResponse(
    val id: UUID,
    val code: String,
    val contractVersion: String,
    val name: String,
    val description: String,
    @field:Schema(allowableValues = ["TEXT", "MARKDOWN", "STRUCTURED", "JSON"])
    val responseFormat: AgentResponseFormat,
    @field:Schema(implementation = JsonNode::class) val inputSchema: JsonNode,
    @field:Schema(implementation = JsonNode::class) val outputSchema: JsonNode,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(contract: AgentCapability): FunctionContractResponse {
            return FunctionContractResponse(
                id = contract.id,
                code = contract.key,
                contractVersion = contract.contractVersion,
                name = contract.name,
                description = contract.description,
                responseFormat = contract.responseFormat,
                inputSchema = contract.inputSchema,
                outputSchema = contract.outputSchema,
                createdAt = contract.createdAt,
                updatedAt = contract.updatedAt,
            )
        }
    }
}

data class FunctionProviderMetricResponse(
    val agentId: UUID,
    val agentCode: String,
    val agentName: String,
    val versionId: UUID,
    val semver: String,
    @field:Schema(pattern = "^[0-9]+$") val priceAtomic: String,
    val observationCount: Int,
    @field:Schema(nullable = true) val reliabilityPercent: Int?,
    @field:Schema(nullable = true) val p95LatencyMillis: Long?,
    @field:Schema(nullable = true) val contractCompliancePercent: Int?,
    val mature: Boolean,
)
