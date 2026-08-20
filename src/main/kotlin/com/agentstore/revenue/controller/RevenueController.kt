package com.agentstore.revenue.controller

import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.revenue.dto.response.DeveloperRevenueResponse
import com.agentstore.revenue.service.RevenueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/developers")
@AgentStoreErrorResponses
class RevenueController(private val service: RevenueService) {
    @GetMapping("/{id}/revenue")
    @Operation(operationId = "getApiDevelopersByIdRevenue", summary = "Get developer revenue")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: UUID?,
        @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): CommonResponse<DeveloperRevenueResponse> {
        return CommonResponse.success(service.get(id, cursor, limit))
    }
}
