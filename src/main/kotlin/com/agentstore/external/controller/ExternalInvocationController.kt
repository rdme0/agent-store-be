package com.agentstore.external.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.security.dto.ExternalReceiptPrincipal
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.external.dto.request.CreateExternalInvocationRequest
import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import com.agentstore.external.dto.response.ExternalInvocationStatusResponse
import com.agentstore.external.service.ExternalInvocationRateLimiter
import com.agentstore.external.service.ExternalInvocationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@AgentStoreErrorResponses
class ExternalInvocationController(
    private val service: ExternalInvocationService,
    private val rateLimiter: ExternalInvocationRateLimiter,
) {
    companion object {
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        private const val RECEIPT_HEADER = "X-AgentStore-Invocation-Receipt"
        private const val INVOCATION_ID_HEADER = "X-AgentStore-Invocation-Id"
        private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
    }

    @PostMapping("/invocations")
    @Operation(operationId = "postV1Invocations", summary = "Start an external agent invocation")
    @SecurityRequirement(name = "demoBearer")
    @ApiResponse(
        responseCode = "202",
        useReturnTypeSchema = true,
        headers = [
            Header(name = RECEIPT_HEADER, description = "Invocation receipt token", schema = Schema(type = "string")),
            Header(name = INVOCATION_ID_HEADER, description = "Created invocation identifier", schema = Schema(type = "string")),
            Header(name = "Location", description = "Invocation status URL", schema = Schema(type = "string")),
        ],
    )
    fun invoke(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @Valid @RequestBody request: CreateExternalInvocationRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<CommonResponse<ExternalInvocationExecutionResponse>> {
        rateLimiter.requireAllowed(remoteAddress = servletRequest.remoteAddr)
        val result = service.invoke(
            idempotencyKey = idempotencyKey,
            request = request,
        )
        return ResponseEntity.accepted()
            .header(RECEIPT_HEADER, result.receiptToken)
            .header(INVOCATION_ID_HEADER, result.invocationId.toString())
            .header("Location", "/v1/invocations/${result.invocationId}")
            .body(CommonResponse.success(result = result.response))
    }

    @GetMapping("/invocations/{id}")
    @Operation(
        operationId = "getV1InvocationsById",
        summary = "Get an external invocation status",
        parameters = [
            Parameter(
                name = RECEIPT_HEADER,
                description = "Invocation receipt token",
                required = true,
                `in` = ParameterIn.HEADER,
            ),
        ],
    )
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: ExternalReceiptPrincipal,
    ): CommonResponse<ExternalInvocationStatusResponse> {
        return CommonResponse.success(result = service.get(id = id, principal = principal))
    }

    @GetMapping("/invocations/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        operationId = "getV1InvocationsByIdEvents",
        summary = "Stream external invocation events",
        parameters = [
            Parameter(
                name = RECEIPT_HEADER,
                description = "Invocation receipt token",
                required = true,
                `in` = ParameterIn.HEADER,
            ),
        ],
    )
    @ApiResponse(
        responseCode = "200",
        description = "Server-sent event stream",
        content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(type = "string"))],
    )
    fun events(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: ExternalReceiptPrincipal,
        @RequestHeader(LAST_EVENT_ID_HEADER, required = false) lastEventId: String?,
    ): SseEmitter {
        return service.subscribe(id = id, principal = principal, lastEventId = lastEventId)
    }
}
