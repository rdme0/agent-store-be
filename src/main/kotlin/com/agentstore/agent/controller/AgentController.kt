package com.agentstore.agent.controller

import com.agentstore.agent.dto.request.AgentListRequest
import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.dto.request.UpdateAgentRequest
import com.agentstore.agent.model.vo.AgentView
import com.agentstore.agent.dto.response.AgentListResponse
import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.service.AgentService
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.web.AgentStoreErrorResponses
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springdoc.core.annotations.ParameterObject
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@AgentStoreErrorResponses
class AgentController(private val service: AgentService) {
    @GetMapping("/agents")
    @Operation(operationId = "getApiAgents", summary = "List agents")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun list(@ParameterObject @Valid @ModelAttribute request: AgentListRequest): CommonResponse<AgentListResponse> {
        return CommonResponse.success(
            result = service.list(
                limit = request.limit,
                cursor = request.cursor,
                query = request.q,
                sort = request.sortType(),
                view = request.viewType(),
            ),
        )
    }

    @GetMapping("/agents/{code}")
    @Operation(operationId = "getApiAgentsByCode", summary = "Get agent by code")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun getByCode(
        @PathVariable code: String,
        @RequestParam(defaultValue = "easy") @Pattern(regexp = "easy|developer") view: String,
    ): CommonResponse<AgentResponse> {
        return CommonResponse.success(
            result = service.getByCode(
                code = code,
                view = AgentView.from(value = view),
            ),
        )
    }

    @PostMapping("/agents")
    @Operation(operationId = "postApiAgents", summary = "Create agent")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun create(@Valid @RequestBody request: CreateAgentRequest): CommonResponse<AgentResponse> {
        return CommonResponse.success(result = service.create(request = request))
    }

    @PatchMapping("/agents/{id}")
    @Operation(operationId = "patchApiAgentsById", summary = "Update agent")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateAgentRequest
    ): CommonResponse<AgentResponse> {
        return CommonResponse.success(result = service.update(id = id, request = request))
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
        return CommonResponse.success(result = service.createVersion(agentId = id, request = request))
    }

    @PostMapping("/agent-versions/{id}/publish")
    @Operation(operationId = "postApiAgentVersionsByIdPublish", summary = "Publish agent version")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun publish(@PathVariable id: UUID): CommonResponse<AgentVersionResponse> {
        return CommonResponse.success(result = service.publish(versionId = id))
    }

    @PostMapping("/agent-versions/{id}/disable")
    @Operation(operationId = "postApiAgentVersionsByIdDisable", summary = "Disable agent version")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun disable(@PathVariable id: UUID): CommonResponse<AgentVersionResponse> {
        return CommonResponse.success(result = service.disable(versionId = id))
    }
}
