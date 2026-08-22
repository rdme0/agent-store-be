package com.agentstore.dependency.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.dto.request.UpdateDependencyRequest
import com.agentstore.dependency.dto.response.DependencyResponse
import com.agentstore.dependency.service.DependencyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@AgentStoreErrorResponses
class DependencyController(private val service: DependencyService) {
    @GetMapping("/agent-versions/{id}/dependencies")
    @Operation(operationId = "getApiAgentVersionsByIdDependencies", summary = "List dependencies")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun list(@PathVariable id: UUID): CommonResponse<List<DependencyResponse>> {
        return CommonResponse.success(result = service.list(sourceVersionId = id))
    }

    @PostMapping("/agent-versions/{id}/dependencies")
    @Operation(operationId = "postApiAgentVersionsByIdDependencies", summary = "Create dependency")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun create(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateDependencyRequest
    ): CommonResponse<DependencyResponse> {
        return CommonResponse.success(
            result = service.create(sourceVersionId = id, request = request),
        )
    }

    @PatchMapping("/agent-versions/{id}/dependencies/{dependencyId}")
    @Operation(
        operationId = "patchApiAgentVersionsByIdDependenciesByDependencyId",
        summary = "Update dependency"
    )
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun update(
        @PathVariable id: UUID,
        @PathVariable dependencyId: UUID,
        @Valid @RequestBody request: UpdateDependencyRequest
    ): CommonResponse<DependencyResponse> {
        return CommonResponse.success(
            result = service.update(
                sourceVersionId = id,
                dependencyId = dependencyId,
                request = request,
            ),
        )
    }

    @DeleteMapping("/agent-versions/{id}/dependencies/{dependencyId}")
    @Operation(
        operationId = "deleteApiAgentVersionsByIdDependenciesByDependencyId",
        summary = "Delete dependency"
    )
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun remove(@PathVariable id: UUID, @PathVariable dependencyId: UUID): CommonResponse<Void> {
        service.remove(sourceVersionId = id, dependencyId = dependencyId)
        return CommonResponse.emptySuccess()
    }
}
