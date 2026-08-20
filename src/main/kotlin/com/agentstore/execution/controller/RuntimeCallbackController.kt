package com.agentstore.execution.controller

import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.dto.response.RuntimeDependencyInvocationResponse
import com.agentstore.execution.service.RuntimeCallbackService
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.*

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
    ): RuntimeDependencyInvocationResponse = service.invoke(id, request, authorization, idempotencyKey)
}
