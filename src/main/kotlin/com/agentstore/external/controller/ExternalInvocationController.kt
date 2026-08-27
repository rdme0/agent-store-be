package com.agentstore.external.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.external.dto.request.CreateExternalInvocationIntentRequest
import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import com.agentstore.external.dto.response.ExternalInvocationStatusResponse
import com.agentstore.external.service.ExternalIntentRateLimiter
import com.agentstore.external.service.ExternalInvocationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    private val rateLimiter: ExternalIntentRateLimiter,
) {
    companion object {
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        private const val PAYMENT_REQUIRED_HEADER = "PAYMENT-REQUIRED"
        private const val PAYMENT_RESPONSE_HEADER = "PAYMENT-RESPONSE"
        private const val PAYMENT_SIGNATURE_HEADER = "PAYMENT-SIGNATURE"
        private const val RECEIPT_HEADER = "X-AgentStore-Invocation-Receipt"
        private const val INVOCATION_ID_HEADER = "X-AgentStore-Invocation-Id"
        private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
    }

    @PostMapping("/invocations")
    @Operation(operationId = "postV1Invocations", summary = "Pay and start an external x402 invocation")
    @ApiResponse(responseCode = "202", useReturnTypeSchema = true)
    @ApiResponse(
        responseCode = "402",
        description = "x402 payment is required",
        content = [Content(schema = Schema(implementation = CommonResponse::class))],
    )
    fun invoke(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @RequestHeader(PAYMENT_SIGNATURE_HEADER, required = false) signatureHeader: String?,
        @Valid @RequestBody request: CreateExternalInvocationIntentRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<CommonResponse<ExternalInvocationExecutionResponse>> {
        rateLimiter.requireAllowed(remoteAddress = servletRequest.remoteAddr)
        val result = service.invoke(
            idempotencyKey = idempotencyKey,
            request = request,
            signatureHeader = signatureHeader,
        )
        if (result.response == null) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .header(RECEIPT_HEADER, result.receiptToken)
                .header(INVOCATION_ID_HEADER, result.invocationId.toString())
                .header("Location", "/v1/invocations/${result.invocationId}")
                .header(PAYMENT_REQUIRED_HEADER, requireNotNull(result.paymentRequiredHeader))
                .body(
                    CommonResponse(
                        isSuccess = false,
                        message = ErrorCode.EXTERNAL_PAYMENT_REQUIRED.message,
                        errorCode = ErrorCode.EXTERNAL_PAYMENT_REQUIRED.code,
                        result = null,
                    ),
                )
        }

        return ResponseEntity.accepted()
            .header(RECEIPT_HEADER, result.receiptToken)
            .header(INVOCATION_ID_HEADER, result.invocationId.toString())
            .header("Location", "/v1/invocations/${result.invocationId}")
            .header(PAYMENT_RESPONSE_HEADER, requireNotNull(result.paymentResponseHeader))
            .body(CommonResponse.success(result = requireNotNull(result.response)))
    }

    @GetMapping("/invocations/{id}")
    @Operation(operationId = "getV1InvocationsById", summary = "Get an external invocation status")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(
        @PathVariable id: UUID,
        @RequestHeader(RECEIPT_HEADER, required = false) receiptToken: String?,
    ): CommonResponse<ExternalInvocationStatusResponse> {
        return CommonResponse.success(result = service.get(id = id, receiptToken = receiptToken))
    }

    @GetMapping("/invocations/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(operationId = "getV1InvocationsByIdEvents", summary = "Stream external invocation events")
    @ApiResponse(
        responseCode = "200",
        description = "Server-sent event stream",
        content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(type = "string"))],
    )
    fun events(
        @PathVariable id: UUID,
        @RequestHeader(RECEIPT_HEADER, required = false) receiptToken: String?,
        @RequestHeader(LAST_EVENT_ID_HEADER, required = false) lastEventId: String?,
    ): SseEmitter {
        return service.subscribe(id = id, receiptToken = receiptToken, lastEventId = lastEventId)
    }
}
