package com.agentstore.common.dto.response

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema

data class ApiError(
    val error: ErrorBody,
    val traceId: String,
)

data class ErrorBody(
    val code: String,
    val message: String,
    @field:Schema(implementation = JsonNode::class, nullable = false)
    val details: Any? = null,
)
