package com.agentstore.external.dto.response

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class ExternalInvocationExecutionResponse(
    val id: UUID,
    val executionId: UUID,
    val executionStatus: String,
    @field:Schema(pattern = "^[1-9][0-9]*$") val maxCostAtomic: String,
)

data class ExternalInvocationStatusResponse(
    val id: UUID,
    val executionId: UUID,
    val executionStatus: String,
    @field:Schema(implementation = JsonNode::class, nullable = true) val output: JsonNode? = null,
    @field:Schema(pattern = "^[1-9][0-9]*$") val maxCostAtomic: String,
    val receiptExpiresAt: Instant,
)
