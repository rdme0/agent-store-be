package com.agentstore.agent.controller

import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.dto.request.UpdateAgentRequest
import com.agentstore.agent.dto.response.AgentListResponse
import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.service.AgentService
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.web.AgentStoreErrorResponses
import io.swagger.v3.oas.annotations.Operation
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
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun list(
        @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(50) limit: Int,
        @RequestParam(required = false) cursor: UUID?,
    ): CommonResponse<AgentListResponse> {
        return CommonResponse.success(service.list(limit, cursor))
    }

    @GetMapping("/agents/{slug}")
    @Operation(operationId = "getApiAgentsBySlug", summary = "Get agent by slug")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun getBySlug(@PathVariable slug: String): CommonResponse<AgentResponse> {
        return CommonResponse.success(service.getBySlug(slug))
    }

    @PostMapping("/agents")
    @Operation(operationId = "postApiAgents", summary = "Create agent")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun create(@Valid @RequestBody request: CreateAgentRequest): CommonResponse<AgentResponse> {
        return CommonResponse.success(service.create(request))
    }

    @PatchMapping("/agents/{id}")
    @Operation(operationId = "patchApiAgentsById", summary = "Update agent")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateAgentRequest): CommonResponse<AgentResponse> {
        return CommonResponse.success(service.update(id, request))
    }

    @DeleteMapping("/agents/{id}")
    @Operation(operationId = "deleteApiAgentsById", summary = "Delete agent")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun delete(@PathVariable id: UUID): CommonResponse<Void> {
        service.delete(id)
        return CommonResponse.emptySuccess()
    }

    @PostMapping("/agents/{id}/versions")
    @Operation(operationId = "postApiAgentsByIdVersions", summary = "Create agent version")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun createVersion(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateAgentVersionRequest
    ): CommonResponse<AgentVersionResponse> {
        return CommonResponse.success(service.createVersion(id, request))
    }

    @PostMapping("/agent-versions/{id}/publish")
    @Operation(operationId = "postApiAgentVersionsByIdPublish", summary = "Publish agent version")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun publish(@PathVariable id: UUID): CommonResponse<AgentVersionResponse> {
        return CommonResponse.success(service.publish(id))
    }

    @PostMapping("/agent-versions/{id}/disable")
    @Operation(operationId = "postApiAgentVersionsByIdDisable", summary = "Disable agent version")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun disable(@PathVariable id: UUID): CommonResponse<AgentVersionResponse> {
        return CommonResponse.success(service.disable(id))
    }
}
