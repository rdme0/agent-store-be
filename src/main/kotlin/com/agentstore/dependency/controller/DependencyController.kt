package com.agentstore.dependency.controller

import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.dto.request.UpdateDependencyRequest
import com.agentstore.dependency.dto.response.DependencyResponse
import com.agentstore.dependency.service.DependencyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api")
@AgentStoreErrorResponses
class DependencyController(private val service: DependencyService) {
    @GetMapping("/agent-versions/{id}/dependencies")
    @Operation(operationId = "getApiAgentVersionsByIdDependencies", summary = "List dependencies")
    @ApiResponse(
        responseCode = "200",
        content = [Content(
            mediaType = "application/json",
            array = ArraySchema(schema = Schema(implementation = DependencyResponse::class))
        )]
    )
    fun list(@PathVariable id: UUID): List<DependencyResponse> {
        return service.list(id)
    }

    @PostMapping("/agent-versions/{id}/dependencies")
    @Operation(operationId = "postApiAgentVersionsByIdDependencies", summary = "Create dependency")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(
        responseCode = "201",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = DependencyResponse::class))]
    )
    fun create(@PathVariable id: UUID, @Valid @RequestBody request: CreateDependencyRequest): DependencyResponse {
        return service.create(id, request)
    }

    @PatchMapping("/agent-versions/{id}/dependencies/{dependencyId}")
    @Operation(operationId = "patchApiAgentVersionsByIdDependenciesByDependencyId", summary = "Update dependency")
    @ApiResponse(
        responseCode = "200",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = DependencyResponse::class))]
    )
    fun update(
        @PathVariable id: UUID,
        @PathVariable dependencyId: UUID,
        @Valid @RequestBody request: UpdateDependencyRequest
    ): DependencyResponse {
        return service.update(id, dependencyId, request)
    }

    @DeleteMapping("/agent-versions/{id}/dependencies/{dependencyId}")
    @Operation(operationId = "deleteApiAgentVersionsByIdDependenciesByDependencyId", summary = "Delete dependency")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204")
    fun remove(@PathVariable id: UUID, @PathVariable dependencyId: UUID) {
        service.remove(id, dependencyId)
    }
}
