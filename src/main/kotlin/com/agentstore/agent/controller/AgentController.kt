package com.agentstore.agent.controller

import com.agentstore.agent.dto.request.AgentListRequest
import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.dto.request.UpdateAgentRequest
import com.agentstore.agent.dto.response.AgentListResponse
import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.service.AgentService
import com.agentstore.agent.service.ProviderReadinessService
import com.agentstore.agent.dto.response.AgentVersionReadinessResponse
import com.agentstore.agent.dto.request.VerificationInputRequest
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.security.dto.DemoDeveloperPrincipal
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.developer.service.DemoDeveloperAccessService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
class AgentController(
    private val service: AgentService,
    private val readinessService: ProviderReadinessService,
    private val demoDeveloperAccessService: DemoDeveloperAccessService,
) {
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
                usageType = request.usageTypeValue(),
            ),
        )
    }

    @GetMapping("/agents/{code}")
    @Operation(operationId = "getApiAgentsByCode", summary = "Get agent by code")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun getByCode(@PathVariable code: String): CommonResponse<AgentResponse> {
        return CommonResponse.success(
            result = service.getByCode(
                code = code,
            ),
        )
    }

    @PostMapping("/agents")
    @Operation(operationId = "postApiAgents", summary = "Create agent")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun create(
        @AuthenticationPrincipal principal: DemoDeveloperPrincipal,
        @Valid @RequestBody request: CreateAgentRequest,
    ): CommonResponse<AgentResponse> {
        return CommonResponse.success(result = service.create(request = request.copy(developerId = principal.developerId)))
    }

    @PatchMapping("/agents/{id}")
    @Operation(operationId = "patchApiAgentsById", summary = "Update agent")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun update(
        @AuthenticationPrincipal principal: DemoDeveloperPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateAgentRequest
    ): CommonResponse<AgentResponse> {
        demoDeveloperAccessService.requireAgentOwner(agentId = id, principal = principal)
        return CommonResponse.success(result = service.update(id = id, request = request))
    }

    @DeleteMapping("/agents/{id}")
    @Operation(operationId = "deleteApiAgentsById", summary = "Delete agent")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun delete(@AuthenticationPrincipal principal: DemoDeveloperPrincipal, @PathVariable id: UUID): CommonResponse<Void> {
        demoDeveloperAccessService.requireAgentOwner(agentId = id, principal = principal)
        service.delete(id)
        return CommonResponse.emptySuccess()
    }

    @PostMapping("/agents/{id}/versions")
    @Operation(operationId = "postApiAgentsByIdVersions", summary = "Create agent version")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun createVersion(
        @AuthenticationPrincipal principal: DemoDeveloperPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateAgentVersionRequest
    ): CommonResponse<AgentVersionResponse> {
        demoDeveloperAccessService.requireAgentOwner(agentId = id, principal = principal)
        return CommonResponse.success(result = service.createVersion(agentId = id, request = request))
    }

    @PostMapping("/agent-versions/{id}/publish")
    @Operation(operationId = "postApiAgentVersionsByIdPublish", summary = "Publish agent version")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun publish(@AuthenticationPrincipal principal: DemoDeveloperPrincipal, @PathVariable id: UUID): CommonResponse<AgentVersionResponse> {
        demoDeveloperAccessService.requireVersionOwner(versionId = id, principal = principal)
        return CommonResponse.success(result = readinessService.publish(versionId = id))
    }

    @PostMapping("/agent-versions/{id}/verify")
    @Operation(operationId = "postApiAgentVersionsByIdVerify", summary = "Verify active agent version")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun verify(@AuthenticationPrincipal principal: DemoDeveloperPrincipal, @PathVariable id: UUID): CommonResponse<AgentVersionResponse> {
        demoDeveloperAccessService.requireVersionOwner(versionId = id, principal = principal)
        return CommonResponse.success(result = readinessService.verify(versionId = id))
    }

    @PostMapping("/agent-versions/{id}/verification-input/backfill")
    @Operation(operationId = "postApiAgentVersionsByIdVerificationInputBackfill", summary = "Backfill legacy verification input")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun backfillVerificationInput(
        @AuthenticationPrincipal principal: DemoDeveloperPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: VerificationInputRequest,
    ): CommonResponse<Void> {
        demoDeveloperAccessService.requireVersionOwner(versionId = id, principal = principal)
        service.backfillVerificationInput(versionId = id, verificationInput = request.verificationInput)
        return CommonResponse.emptySuccess()
    }

    @GetMapping("/agent-versions/{id}/readiness")
    @Operation(operationId = "getApiAgentVersionsByIdReadiness", summary = "Get provider readiness")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun readiness(
        @AuthenticationPrincipal principal: DemoDeveloperPrincipal,
        @PathVariable id: UUID,
    ): CommonResponse<AgentVersionReadinessResponse> {
        demoDeveloperAccessService.requireVersionOwner(versionId = id, principal = principal)
        return CommonResponse.success(result = readinessService.readiness(versionId = id))
    }

    @PostMapping("/agent-versions/{id}/disable")
    @Operation(operationId = "postApiAgentVersionsByIdDisable", summary = "Disable agent version")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun disable(@AuthenticationPrincipal principal: DemoDeveloperPrincipal, @PathVariable id: UUID): CommonResponse<AgentVersionResponse> {
        demoDeveloperAccessService.requireVersionOwner(versionId = id, principal = principal)
        return CommonResponse.success(result = service.disable(versionId = id))
    }
}
