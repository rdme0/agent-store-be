package com.agentstore.payment

import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import com.agentstore.payment.model.vo.PaymentMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.UUID

class PaymentAttemptTest {
    @Test
    fun `settlement keeps durable payment evidence on the attempt`() {
        val attempt = PaymentAttempt(UUID.randomUUID(), UUID.randomUUID(), BigInteger.valueOf(7), "eip155:84532", "USDC", "0xreceiver", PaymentMode.SIMULATED)
        attempt.settled("simulated:tx", "simulated:payment")

        assertEquals(PaymentAttemptStatus.SETTLED, attempt.status)
        assertEquals("simulated:tx", attempt.transactionHash)
        assertEquals("simulated:payment", attempt.paymentIdentifier)
    }

    @Test
    fun `settled attempt cannot be downgraded by a later failure`() {
        val attempt = PaymentAttempt(UUID.randomUUID(), UUID.randomUUID(), BigInteger.ONE, "eip155:84532", "USDC", "0xreceiver", PaymentMode.SIMULATED)
        attempt.settled("simulated:tx", null)
        attempt.failed("late_failure")
        attempt.reconciliationRequired("late_reconciliation")

        assertEquals(PaymentAttemptStatus.SETTLED, attempt.status)
    }
}
