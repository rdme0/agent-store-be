package com.agentstore.common.web

import com.agentstore.common.dto.response.CommonResponse
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
            responseCode = "401",
            description = "Unauthorized",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "400",
            description = "Bad request",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "404",
            description = "Not found",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "409",
            description = "Conflict",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "422",
            description = "Validation error",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "502",
            description = "Upstream failure",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "503",
            description = "Service unavailable",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonResponse::class)
            )]
        ),
    ],
)
annotation class AgentStoreErrorResponses
