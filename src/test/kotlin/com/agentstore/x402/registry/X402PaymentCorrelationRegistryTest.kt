package com.agentstore.x402.registry

import com.agentstore.payment.dto.internal.PaymentInvocationResultDto
import com.agentstore.payment.model.vo.PaymentReconciliationStatus
import com.fasterxml.jackson.databind.node.NullNode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class X402PaymentCorrelationRegistryTest {
    @Test
    fun `concurrent duplicate coalesces to one payment result`() {
        val registry = X402PaymentCorrelationRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                executor.submit<PaymentInvocationResultDto> {
                    registry.claim("attempt", "key", "fingerprint") {
                        calls.incrementAndGet()
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        settled()
                    }
                }
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            release.countDown()

            assertThat(tasks.map {
                it.get(
                    5,
                    TimeUnit.SECONDS
                ).transactionHash
            }).containsOnly("0x${"a".repeat(64)}")
            assertThat(calls).hasValue(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `reconcile only returns an exact recorded settlement`() {
        val registry = X402PaymentCorrelationRegistry()
        registry.claim("attempt", "key", "fingerprint") { settled() }

        assertThat(registry.reconcile("attempt", "key").status).isEqualTo(
            PaymentReconciliationStatus.SETTLED
        )
        assertThat(registry.reconcile("attempt", "other").status).isEqualTo(
            PaymentReconciliationStatus.UNKNOWN
        )
        assertThat(X402PaymentCorrelationRegistry().reconcile("attempt", "key").status)
            .isEqualTo(PaymentReconciliationStatus.UNKNOWN)
    }

    private fun settled(): PaymentInvocationResultDto {
        return PaymentInvocationResultDto(
            output = NullNode.instance,
            transactionHash = "0x${"a".repeat(64)}",
            paymentIdentifier = "0x${"a".repeat(64)}",
            agentStatus = 200,
        )
    }
}
