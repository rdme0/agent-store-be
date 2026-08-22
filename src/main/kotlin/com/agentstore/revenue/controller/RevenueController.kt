package com.agentstore.revenue.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.revenue.dto.request.RevenueQueryRequest
import com.agentstore.revenue.dto.response.DeveloperRevenueResponse
import com.agentstore.revenue.service.RevenueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/developers")
@AgentStoreErrorResponses
class RevenueController(private val service: RevenueService) {
    @GetMapping("/{id}/revenue")
    @Operation(operationId = "getApiDevelopersByIdRevenue", summary = "Get developer revenue")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(
        @PathVariable id: UUID,
        @Valid @ModelAttribute request: RevenueQueryRequest,
    ): CommonResponse<DeveloperRevenueResponse> {
        return CommonResponse.success(
            result = service.get(
                developerId = id,
                cursor = request.cursor,
                limit = request.limit,
            ),
        )
    }
}
