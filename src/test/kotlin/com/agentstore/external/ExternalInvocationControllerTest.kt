package com.agentstore.external

import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.external.controller.ExternalInvocationController
import com.agentstore.external.dto.internal.ExternalInvocationExecuteResultDto
import com.agentstore.external.service.ExternalIntentRateLimiter
import com.agentstore.external.service.ExternalInvocationService
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ExternalInvocationControllerTest {
    @Test
    fun `unsigned execute returns x402 challenge without an execution result`() {
        val service = mock(ExternalInvocationService::class.java)
        val id = UUID.randomUUID()
        `when`(service.execute(id = id, receiptToken = "receipt-token", signatureHeader = null)).thenReturn(
            ExternalInvocationExecuteResultDto(
                paymentRequiredHeader = "encoded-payment-required",
                paymentResponseHeader = null,
                response = null,
            ),
        )
        val controller = ExternalInvocationController(
            service = service,
            rateLimiter = mock(ExternalIntentRateLimiter::class.java),
        )

        val response = controller.execute(
            id = id,
            receiptToken = "receipt-token",
            signatureHeader = null,
        )

        assertEquals(402, response.statusCode.value())
        assertEquals("encoded-payment-required", response.headers.getFirst("PAYMENT-REQUIRED"))
        assertEquals(ErrorCode.EXTERNAL_PAYMENT_REQUIRED.code, response.body?.errorCode)
        assertNull(response.body?.result)
    }
}
