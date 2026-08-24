package com.agentstore.external.controller

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.web.AgentStoreErrorResponses
import com.agentstore.external.dto.request.CreateExternalInvocationIntentRequest
import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import com.agentstore.external.dto.response.ExternalInvocationIntentResponse
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
        private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
    }

    @PostMapping("/invocation-intents")
    @Operation(operationId = "postV1InvocationIntents", summary = "Create an external x402 invocation intent")
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    fun createIntent(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateExternalInvocationIntentRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<CommonResponse<ExternalInvocationIntentResponse>> {
        rateLimiter.requireAllowed(remoteAddress = servletRequest.remoteAddr)
        val created = service.createIntent(idempotencyKey = idempotencyKey, request = request)

        return ResponseEntity.status(HttpStatus.CREATED)
            .header(RECEIPT_HEADER, created.receiptToken)
            .body(CommonResponse.success(result = created.response))
    }

    @PostMapping("/invocation-intents/{id}/execute")
    @Operation(operationId = "postV1InvocationIntentsByIdExecute", summary = "Pay and start an invocation")
    @ApiResponse(
        responseCode = "202",
        description = "Payment was settled and the asynchronous execution was created",
        useReturnTypeSchema = true,
    )
    @ApiResponse(
        responseCode = "402",
        description = "x402 payment is required",
        content = [Content(schema = Schema(implementation = CommonResponse::class))],
    )
    fun execute(
        @PathVariable id: UUID,
        @RequestHeader(RECEIPT_HEADER, required = false) receiptToken: String?,
        @RequestHeader(PAYMENT_SIGNATURE_HEADER, required = false) signatureHeader: String?,
    ): ResponseEntity<CommonResponse<ExternalInvocationExecutionResponse>> {
        val result = service.execute(
            id = id,
            receiptToken = receiptToken,
            signatureHeader = signatureHeader,
        )
        val response = result.response
        if (response == null) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
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
            .header(PAYMENT_RESPONSE_HEADER, requireNotNull(result.paymentResponseHeader))
            .body(CommonResponse.success(result = response))
    }

    @GetMapping("/invocation-intents/{id}")
    @Operation(operationId = "getV1InvocationIntentsById", summary = "Get an external invocation status")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    fun get(
        @PathVariable id: UUID,
        @RequestHeader(RECEIPT_HEADER, required = false) receiptToken: String?,
    ): CommonResponse<ExternalInvocationStatusResponse> {
        return CommonResponse.success(result = service.get(id = id, receiptToken = receiptToken))
    }

    @GetMapping("/invocation-intents/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(operationId = "getV1InvocationIntentsByIdEvents", summary = "Stream external invocation events")
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
