package com.agentstore.dependency.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.service.QuoteService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agents")
@AgentStoreErrorResponses
class QuoteController(private val service: QuoteService) {
    @PostMapping("/{code}/quotes")
    @Operation(operationId = "postApiAgentsByCodeQuotes", summary = "Create quote")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun create(
        @PathVariable code: String,
        @Valid @RequestBody(required = false) request: QuoteRequest?
    ): CommonResponse<QuoteResponse> {
        return CommonResponse.success(
            result = service.create(code = code, request = request ?: QuoteRequest()),
        )
    }
}
