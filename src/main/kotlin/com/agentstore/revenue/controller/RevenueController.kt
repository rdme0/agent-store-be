package com.agentstore.revenue.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.security.dto.DemoDeveloperPrincipal
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.developer.service.DemoDeveloperAccessService
import com.agentstore.revenue.dto.request.RevenueQueryRequest
import com.agentstore.revenue.dto.response.DeveloperRevenueResponse
import com.agentstore.revenue.service.RevenueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.security.core.annotation.AuthenticationPrincipal

@RestController
@RequestMapping("/api/developers")
@AgentStoreErrorResponses
class RevenueController(
    private val service: RevenueService,
    private val demoDeveloperAccessService: DemoDeveloperAccessService,
) {
    @GetMapping("/{id}/revenue")
    @SecurityRequirement(name = "demoBearer")
    @Operation(operationId = "getApiDevelopersByIdRevenue", summary = "Get developer revenue")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(
        @AuthenticationPrincipal principal: DemoDeveloperPrincipal,
        @PathVariable id: UUID,
        @ParameterObject @Valid @ModelAttribute request: RevenueQueryRequest,
    ): CommonResponse<DeveloperRevenueResponse> {
        demoDeveloperAccessService.requireOwner(ownerId = id, principal = principal)
        return CommonResponse.success(
            result = service.get(
                developerId = id,
                cursor = request.cursor,
                limit = request.limit,
            ),
        )
    }
}
