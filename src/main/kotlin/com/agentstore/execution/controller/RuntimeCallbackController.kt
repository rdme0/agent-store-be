package com.agentstore.execution.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.dto.response.RuntimeDependencyInvocationResponse
import com.agentstore.execution.service.RuntimeCallbackService
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Hidden
@RestController
@RequestMapping("/api/runtime/executions")
class RuntimeCallbackController(private val service: RuntimeCallbackService) {
    @PostMapping("/{id}/dependencies/invoke")
    fun invoke(
        @PathVariable id: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: RuntimeDependencyInvocationRequest,
    ): CommonResponse<RuntimeDependencyInvocationResponse> {
        return CommonResponse.success(
            service.invoke(
                executionId = id,
                request = request,
                authorization = authorization,
                idempotencyKey = idempotencyKey,
            )
        )
    }
}
