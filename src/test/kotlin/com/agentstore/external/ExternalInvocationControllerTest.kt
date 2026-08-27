package com.agentstore.external

import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.external.controller.ExternalInvocationController
import com.agentstore.external.dto.internal.ExternalInvocationResultDto
import com.agentstore.external.dto.request.CreateExternalInvocationIntentRequest
import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import com.agentstore.external.model.vo.ExternalInvocationStatus
import com.agentstore.external.service.ExternalIntentRateLimiter
import com.agentstore.external.service.ExternalInvocationService
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ExternalInvocationControllerTest {
    @Test
    fun `unsigned invocation returns x402 challenge without an execution result`() {
        val service = mock(ExternalInvocationService::class.java)
        val request = CreateExternalInvocationIntentRequest(
            agentCode = "weather",
            versionConstraint = "*",
            maxTotalAtomic = "1000",
        )
        `when`(service.invoke(idempotencyKey = "idempotency-key-0001", request = request, signatureHeader = null)).thenReturn(
            ExternalInvocationResultDto(
                invocationId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                receiptToken = "receipt-token",
                paymentRequiredHeader = "encoded-payment-required",
                paymentResponseHeader = null,
                response = null,
            ),
        )
        val controller = ExternalInvocationController(
            service = service,
            rateLimiter = mock(ExternalIntentRateLimiter::class.java),
        )

        val servletRequest = mock(HttpServletRequest::class.java)
        `when`(servletRequest.remoteAddr).thenReturn("127.0.0.1")
        val response = controller.invoke(
            idempotencyKey = "idempotency-key-0001",
            signatureHeader = null,
            request = request,
            servletRequest = servletRequest,
        )

        assertEquals(402, response.statusCode.value())
        assertEquals("receipt-token", response.headers.getFirst("X-AgentStore-Invocation-Receipt"))
        assertEquals("00000000-0000-0000-0000-000000000001", response.headers.getFirst("X-AgentStore-Invocation-Id"))
        assertEquals("/v1/invocations/00000000-0000-0000-0000-000000000001", response.headers.getFirst("Location"))
        assertEquals("encoded-payment-required", response.headers.getFirst("PAYMENT-REQUIRED"))
        assertEquals(ErrorCode.EXTERNAL_PAYMENT_REQUIRED.code, response.body?.errorCode)
        assertNull(response.body?.result)
    }

    @Test
    fun `signed retry returns the same invocation resource and payment response`() {
        val service = mock(ExternalInvocationService::class.java)
        val request = CreateExternalInvocationIntentRequest(
            agentCode = "weather",
            versionConstraint = "*",
            maxTotalAtomic = "1000",
        )
        val invocationId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        `when`(
            service.invoke(
                idempotencyKey = "idempotency-key-0002",
                request = request,
                signatureHeader = "payment-signature",
            ),
        ).thenReturn(
            ExternalInvocationResultDto(
                invocationId = invocationId,
                receiptToken = "receipt-token",
                paymentRequiredHeader = null,
                paymentResponseHeader = "encoded-payment-response",
                response = ExternalInvocationExecutionResponse(
                    id = invocationId,
                    executionId = UUID.randomUUID(),
                    status = ExternalInvocationStatus.EXECUTION_CREATED,
                    totalCostAtomic = "1000",
                ),
            ),
        )
        val controller = ExternalInvocationController(
            service = service,
            rateLimiter = mock(ExternalIntentRateLimiter::class.java),
        )
        val servletRequest = mock(HttpServletRequest::class.java)
        `when`(servletRequest.remoteAddr).thenReturn("127.0.0.1")

        val response = controller.invoke(
            idempotencyKey = "idempotency-key-0002",
            signatureHeader = "payment-signature",
            request = request,
            servletRequest = servletRequest,
        )

        assertEquals(202, response.statusCode.value())
        assertEquals("receipt-token", response.headers.getFirst("X-AgentStore-Invocation-Receipt"))
        assertEquals(invocationId.toString(), response.headers.getFirst("X-AgentStore-Invocation-Id"))
        assertEquals("/v1/invocations/$invocationId", response.headers.getFirst("Location"))
        assertEquals("encoded-payment-response", response.headers.getFirst("PAYMENT-RESPONSE"))
        assertEquals(invocationId, response.body?.result?.id)
    }
}
