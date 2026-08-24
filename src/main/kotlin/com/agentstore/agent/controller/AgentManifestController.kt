package com.agentstore.agent.controller

import com.agentstore.agent.dto.request.AgentManifestRequest
import com.agentstore.agent.dto.response.AgentManifestImportResponse
import com.agentstore.agent.dto.response.AgentManifestResponse
import com.agentstore.agent.dto.response.AgentManifestValidationResponse
import com.agentstore.agent.service.AgentManifestService
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.web.AgentStoreErrorResponses
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@AgentStoreErrorResponses
class AgentManifestController(private val service: AgentManifestService) {
    @PostMapping("/agent-manifests/validate")
    @Operation(operationId = "postApiAgentManifestsValidate", summary = "Validate agent manifest")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun validate(@Valid @RequestBody request: AgentManifestRequest): CommonResponse<AgentManifestValidationResponse> {
        return CommonResponse.success(result = service.validate(request = request))
    }

    @PostMapping("/agent-manifests")
    @Operation(operationId = "postApiAgentManifests", summary = "Import agent manifest")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun import(@Valid @RequestBody request: AgentManifestRequest): CommonResponse<AgentManifestImportResponse> {
        return CommonResponse.success(result = service.import(request = request))
    }

    @GetMapping("/agent-manifests/agent-versions/{id}", "/agent-versions/{id}/manifest")
    @Operation(operationId = "getApiAgentManifestsAgentVersionsById", summary = "Export agent manifest")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun export(@PathVariable id: UUID): CommonResponse<AgentManifestResponse> {
        return CommonResponse.success(result = service.export(versionId = id))
    }

    @PutMapping("/agent-versions/{id}/manifest")
    @Operation(operationId = "putApiAgentVersionsByIdManifest", summary = "Replace draft agent manifest")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun replace(
        @PathVariable id: UUID,
        @Valid @RequestBody request: AgentManifestRequest,
    ): CommonResponse<AgentManifestResponse> {
        return CommonResponse.success(result = service.replace(versionId = id, request = request))
    }
}
