package com.agentstore.external.dto.response

import com.agentstore.external.model.vo.ExternalInvocationStatus
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class ExternalInvocationIntentResponse(
    val id: UUID,
    val executeUrl: String,
    @field:Schema(pattern = "^[1-9][0-9]*$") val providerCostAtomic: String,
    @field:Schema(pattern = "^[0-9]+$") val platformFeeAtomic: String,
    @field:Schema(pattern = "^[1-9][0-9]*$") val totalCostAtomic: String,
    val expiresAt: Instant,
)

data class ExternalInvocationExecutionResponse(
    val id: UUID,
    val status: ExternalInvocationStatus,
    val executionId: UUID,
    @field:Schema(pattern = "^[1-9][0-9]*$") val totalCostAtomic: String,
)

data class ExternalInvocationStatusResponse(
    val id: UUID,
    val status: ExternalInvocationStatus,
    @field:Schema(nullable = true) val executionId: UUID? = null,
    @field:Schema(nullable = true) val executionStatus: String? = null,
    @field:Schema(implementation = JsonNode::class, nullable = true) val output: JsonNode? = null,
    @field:Schema(pattern = "^[1-9][0-9]*$") val providerCostAtomic: String,
    @field:Schema(pattern = "^[0-9]+$") val platformFeeAtomic: String,
    @field:Schema(pattern = "^[1-9][0-9]*$") val totalCostAtomic: String,
    val expiresAt: Instant,
)
