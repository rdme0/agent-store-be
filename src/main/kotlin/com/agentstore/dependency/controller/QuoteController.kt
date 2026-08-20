package com.agentstore.dependency.controller

import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.service.QuoteService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/agents")
@AgentStoreErrorResponses
class QuoteController(private val service: QuoteService) {
    @PostMapping("/{slug}/quotes")
    @Operation(operationId = "postApiAgentsBySlugQuotes", summary = "Create quote")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun create(
        @PathVariable slug: String,
        @Valid @RequestBody(required = false) request: QuoteRequest?
    ): CommonResponse<QuoteResponse> {
        return CommonResponse.success(service.create(slug, request ?: QuoteRequest()))
    }
}
