package com.agentstore.execution.controller

import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.execution.dto.request.CreateExecutionRequest
import com.agentstore.execution.dto.response.ExecutionResponse
import com.agentstore.execution.service.ExecutionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*

@RestController
@RequestMapping("/api/executions")
@AgentStoreErrorResponses
class ExecutionController(private val service: ExecutionService) {
    @PostMapping
    @Operation(operationId = "postApiExecutions", summary = "Create execution")
    @ApiResponse(responseCode = "202", useReturnTypeSchema = true)
    fun create(@Valid @RequestBody request: CreateExecutionRequest): ResponseEntity<CommonResponse<ExecutionResponse>> {
        return ResponseEntity.accepted().body(CommonResponse.success(service.create(request)))
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getApiExecutionsById", summary = "Get execution")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(@PathVariable id: UUID): CommonResponse<ExecutionResponse> {
        return CommonResponse.success(service.get(id))
    }

    @GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(operationId = "getApiExecutionsByIdEvents", summary = "Stream execution events")
    @ApiResponse(
        responseCode = "200",
        description = "Server-sent events stream",
        content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(type = "string"))]
    )
    fun events(
        @PathVariable id: UUID,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?
    ): SseEmitter {
        return service.subscribe(id, lastEventId)
    }
}
