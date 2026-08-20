package com.agentstore.agent.controller

import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.dto.request.UpdateAgentRequest
import com.agentstore.agent.dto.response.AgentListResponse
import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.service.AgentService
import com.agentstore.common.web.AgentStoreErrorResponses
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api")
@AgentStoreErrorResponses
class AgentController(private val service: AgentService) {
    @GetMapping("/agents")
    @Operation(operationId = "getApiAgents", summary = "List agents")
    @ApiResponse(
        responseCode = "200",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = AgentListResponse::class))]
    )
    fun list(
        @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(50) limit: Int,
        @RequestParam(required = false) cursor: UUID?,
    ): AgentListResponse {
        return service.list(limit, cursor)
    }

    @GetMapping("/agents/{slug}")
    @Operation(operationId = "getApiAgentsBySlug", summary = "Get agent by slug")
    @ApiResponse(
        responseCode = "200",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = AgentResponse::class))]
    )
    fun getBySlug(@PathVariable slug: String): AgentResponse {
        return service.getBySlug(slug)
    }

    @PostMapping("/agents")
    @Operation(operationId = "postApiAgents", summary = "Create agent")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(
        responseCode = "201",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = AgentResponse::class))]
    )
    fun create(@Valid @RequestBody request: CreateAgentRequest): AgentResponse {
        return service.create(request)
    }

    @PatchMapping("/agents/{id}")
    @Operation(operationId = "patchApiAgentsById", summary = "Update agent")
    @ApiResponse(
        responseCode = "200",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = AgentResponse::class))]
    )
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateAgentRequest): AgentResponse {
        return service.update(id, request)
    }

    @DeleteMapping("/agents/{id}")
    @Operation(operationId = "deleteApiAgentsById", summary = "Delete agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204")
    fun delete(@PathVariable id: UUID) {
        service.delete(id)
    }

    @PostMapping("/agents/{id}/versions")
    @Operation(operationId = "postApiAgentsByIdVersions", summary = "Create agent version")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(
        responseCode = "201",
        content = [Content(
            mediaType = "application/json",
            schema = Schema(implementation = AgentVersionResponse::class)
        )]
    )
    fun createVersion(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateAgentVersionRequest
    ): AgentVersionResponse {
        return service.createVersion(id, request)
    }

    @PostMapping("/agent-versions/{id}/publish")
    @Operation(operationId = "postApiAgentVersionsByIdPublish", summary = "Publish agent version")
    @ApiResponse(
        responseCode = "200",
        content = [Content(
            mediaType = "application/json",
            schema = Schema(implementation = AgentVersionResponse::class)
        )]
    )
    fun publish(@PathVariable id: UUID): AgentVersionResponse {
        return service.publish(id)
    }

    @PostMapping("/agent-versions/{id}/disable")
    @Operation(operationId = "postApiAgentVersionsByIdDisable", summary = "Disable agent version")
    @ApiResponse(
        responseCode = "200",
        content = [Content(
            mediaType = "application/json",
            schema = Schema(implementation = AgentVersionResponse::class)
        )]
    )
    fun disable(@PathVariable id: UUID): AgentVersionResponse {
        return service.disable(id)
    }
}
