package com.agentstore.agent.controller

import com.agentstore.agent.dto.request.CreateFunctionContractRequest
import com.agentstore.agent.dto.response.FunctionContractResponse
import com.agentstore.agent.dto.response.FunctionProviderMetricResponse
import com.agentstore.agent.service.FunctionContractService
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/function-contracts")
@AgentStoreErrorResponses
class FunctionContractController(private val service: FunctionContractService) {
    @PostMapping
    @Operation(operationId = "postApiFunctionContracts", summary = "Create function contract")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun create(
        @Valid @RequestBody request: CreateFunctionContractRequest,
    ): CommonResponse<FunctionContractResponse> {
        return CommonResponse.success(result = service.create(request = request))
    }

    @GetMapping
    @Operation(operationId = "getApiFunctionContracts", summary = "List function contracts")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun list(): CommonResponse<List<FunctionContractResponse>> {
        return CommonResponse.success(result = service.list())
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getApiFunctionContractsById", summary = "Get function contract")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(@PathVariable id: UUID): CommonResponse<FunctionContractResponse> {
        return CommonResponse.success(result = service.get(id = id))
    }

    @GetMapping("/{id}/providers")
    @Operation(operationId = "getApiFunctionContractsByIdProviders", summary = "List function providers")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun providers(@PathVariable id: UUID): CommonResponse<List<FunctionProviderMetricResponse>> {
        return CommonResponse.success(result = service.providerMetrics(id = id))
    }
}
