package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.agentstore.payment.model.vo.PaymentMode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class X402BridgePaymentClientTest {
    @Test
    fun `unknown post signature result is surfaced for reconciliation without exposing payment material`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/internal/payments/pay-and-invoke") { exchange ->
                val response = """{"outcome":"UNKNOWN_AFTER_SIGNATURE","code":"PAYMENT_RECONCILIATION_REQUIRED"}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }
        try {
            val client = X402BridgePaymentClient(
                "http://127.0.0.1:${server.address.port}",
                "local-test-bridge-secret",
                jacksonObjectMapper()
            )
            assertThat(client.mode).isEqualTo(PaymentMode.X402)
            assertThatThrownBy { client.invoke(request()) }
                .isInstanceOf(PaymentOutcomeUnknownException::class.java)
                .hasMessage("PAYMENT_RECONCILIATION_REQUIRED")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `bridge endpoint and secret fail closed before any payment request`() {
        assertThatThrownBy {
            X402BridgePaymentClient(
                "http://localhost:8091",
                "local-test-bridge-secret",
                jacksonObjectMapper()
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            X402BridgePaymentClient(
                "http://127.0.0.1:8091",
                "short",
                jacksonObjectMapper()
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun request(): PaymentInvocationRequest {
        return PaymentInvocationRequest(
            paymentAttemptId = "attempt",
            idempotencyKey = "key",
            invocationToken = "token",
            endpoint = "https://agent.example.com/invoke",
            amountAtomic = "1",
            maxPriceAtomic = "1",
            network = "eip155:84532",
            asset = "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
            payTo = "0x0000000000000000000000000000000000000001",
            body = mapOf("input" to "test"),
        )
    }
}
