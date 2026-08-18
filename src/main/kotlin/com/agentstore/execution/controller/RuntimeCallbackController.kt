package com.agentstore.execution.controller

import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.dto.response.RuntimeDependencyInvocationResponse
import com.agentstore.execution.service.RuntimeCallbackService
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

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
