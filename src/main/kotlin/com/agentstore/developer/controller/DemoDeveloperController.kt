package com.agentstore.developer.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.security.dto.DemoDeveloperPrincipal
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.developer.dto.response.DemoAccessResponse
import com.agentstore.developer.dto.response.DemoDeveloperResponse
import com.agentstore.developer.service.DemoAccessService
import com.agentstore.developer.service.DemoDeveloperAccessService
import com.agentstore.agent.dto.response.AgentResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@AgentStoreErrorResponses
class DemoDeveloperController(
    private val accessService: DemoDeveloperAccessService,
    private val demoAccessService: DemoAccessService,
) {
    @PostMapping("/demo/access")
    @Operation(operationId = "postApiDemoAccess", summary = "Issue shared demo developer access token")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun access(): CommonResponse<DemoAccessResponse> {
        return CommonResponse.success(
            result = demoAccessService.issue(),
        )
    }

    @GetMapping("/developer/me")
    @Operation(operationId = "getApiDeveloperMe", summary = "Get shared demo developer")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun me(@AuthenticationPrincipal principal: DemoDeveloperPrincipal): CommonResponse<DemoDeveloperResponse> {
        return CommonResponse.success(result = accessService.me(principal = principal))
    }

    @GetMapping("/developer/agents")
    @Operation(operationId = "getApiDeveloperAgents", summary = "List shared demo developer agents")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun agents(@AuthenticationPrincipal principal: DemoDeveloperPrincipal): CommonResponse<List<AgentResponse>> {
        return CommonResponse.success(result = accessService.ownedAgents(principal = principal))
    }
}
