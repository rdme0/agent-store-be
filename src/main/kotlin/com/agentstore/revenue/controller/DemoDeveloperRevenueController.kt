package com.agentstore.revenue.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.security.dto.DemoDeveloperPrincipal
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.revenue.dto.request.RevenueQueryRequest
import com.agentstore.revenue.dto.response.DeveloperRevenueResponse
import com.agentstore.revenue.service.RevenueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/developer")
@AgentStoreErrorResponses
class DemoDeveloperRevenueController(
    private val service: RevenueService,
) {
    @GetMapping("/revenue")
    @Operation(operationId = "getApiDeveloperRevenue", summary = "Get shared demo developer revenue")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(
        @AuthenticationPrincipal principal: DemoDeveloperPrincipal,
        @ParameterObject @Valid @ModelAttribute request: RevenueQueryRequest,
    ): CommonResponse<DeveloperRevenueResponse> {
        return CommonResponse.success(
            result = service.get(
                developerId = principal.developerId,
                cursor = request.cursor,
                limit = request.limit,
            ),
        )
    }
}
