package com.agentstore.external

import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.external.controller.ExternalInvocationController
import com.agentstore.external.dto.internal.ExternalInvocationResultDto
import com.agentstore.external.dto.request.CreateExternalInvocationIntentRequest
import com.agentstore.external.service.ExternalIntentRateLimiter
import com.agentstore.external.service.ExternalInvocationService
import jakarta.servlet.http.HttpServletRequest
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
        assertEquals("encoded-payment-required", response.headers.getFirst("PAYMENT-REQUIRED"))
        assertEquals(ErrorCode.EXTERNAL_PAYMENT_REQUIRED.code, response.body?.errorCode)
        assertNull(response.body?.result)
    }
}
