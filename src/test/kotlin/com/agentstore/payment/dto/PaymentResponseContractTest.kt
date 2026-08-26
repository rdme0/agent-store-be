package com.agentstore.payment.dto

import com.agentstore.execution.dto.response.PaymentAttemptResponse
import com.agentstore.revenue.dto.response.RevenueEntryResponse
import com.agentstore.revenue.model.vo.RevenueType
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaymentResponseContractTest {
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `payment API responses expose settlement evidence without a payment mode`() {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "payment" to PaymentAttemptResponse(
                    id = UUID.randomUUID(),
                    status = "SETTLED",
                    amountAtomic = "1000",
                    transactionHash = "0x${"a".repeat(64)}",
                    paymentIdentifier = "0x${"a".repeat(64)}",
                    failureCode = null,
                ),
                "revenue" to RevenueEntryResponse(
                    id = UUID.randomUUID(),
                    executionStepId = UUID.randomUUID(),
                    paymentAttemptId = UUID.randomUUID(),
                    type = RevenueType.DIRECT,
                    amountAtomic = "1000",
                    transactionHash = "0x${"a".repeat(64)}",
                    paymentIdentifier = "0x${"a".repeat(64)}",
                    createdAt = Instant.parse("2026-08-26T00:00:00Z"),
                ),
            ),
        )

        assertThat(payload).doesNotContain("paymentMode", "\"mode\"")
        assertThat(payload).contains("transactionHash")
    }
}
