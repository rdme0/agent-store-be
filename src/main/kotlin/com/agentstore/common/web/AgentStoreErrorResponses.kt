package com.agentstore.common.web

import com.agentstore.common.dto.response.ApiError

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses

/** Shared error contract used by every public JSON and SSE operation. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponses(
    value = [
        ApiResponse(
            responseCode = "400",
            description = "Bad request",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiError::class))]
        ),
        ApiResponse(
            responseCode = "404",
            description = "Not found",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiError::class))]
        ),
        ApiResponse(
            responseCode = "409",
            description = "Conflict",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiError::class))]
        ),
        ApiResponse(
            responseCode = "422",
            description = "Validation error",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiError::class))]
        ),
        ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiError::class))]
        ),
    ],
)
annotation class AgentStoreErrorResponses
