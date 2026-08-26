package com.agentstore.payment

import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import java.math.BigInteger
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaymentAttemptTest {
    @Test
    fun `settlement keeps durable payment evidence on the attempt`() {
        val attempt = PaymentAttempt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.valueOf(7),
            "eip155:84532",
            "USDC",
            "0xreceiver",
        )
        attempt.settled("0x${"a".repeat(64)}", "0x${"a".repeat(64)}")

        assertEquals(PaymentAttemptStatus.SETTLED, attempt.status)
        assertEquals("0x${"a".repeat(64)}", attempt.transactionHash)
        assertEquals("0x${"a".repeat(64)}", attempt.paymentIdentifier)
    }

    @Test
    fun `settled attempt cannot be downgraded by a later failure`() {
        val attempt = PaymentAttempt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0xreceiver",
        )
        attempt.settled("0x${"b".repeat(64)}", null)
        attempt.failed("late_failure")
        attempt.reconciliationRequired("late_reconciliation")

        assertEquals(PaymentAttemptStatus.SETTLED, attempt.status)
    }
}
