package com.agentstore.payment.client

import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.BridgeReconciliationStatus
import com.agentstore.payment.model.vo.PaymentMode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.util.*

class X402BridgeReconciliationClientTest {
    @Test
    fun `settled response is accepted only from the signed local bridge endpoint`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/internal/payments/reconcile") { exchange ->
                assertThat(exchange.requestHeaders.getFirst("X-AgentStore-Signature")).isNotBlank()
                val response = """{"status":"SETTLED","transactionHash":"0xsettled","paymentIdentifier":"payment-1"}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }
        try {
            val client = X402BridgeReconciliationClient(
                URI("http://127.0.0.1:${server.address.port}"),
                "local-test-bridge-secret",
                jacksonObjectMapper()
            )
            val result = client.reconcile(attempt())
            assertThat(result).isEqualTo(
                com.agentstore.payment.dto.internal.BridgeReconciliationResult(
                    BridgeReconciliationStatus.SETTLED,
                    "0xsettled",
                    "payment-1"
                )
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unknown or unavailable bridge never claims settlement`() {
        val client =
            X402BridgeReconciliationClient(URI("http://127.0.0.1:1"), "local-test-bridge-secret", jacksonObjectMapper())
        assertThat(client.reconcile(attempt()).status).isEqualTo(BridgeReconciliationStatus.UNKNOWN)
    }

    @Test
    fun `validation rejection is definite but server and malformed responses remain unknown`() {
        fun statusFor(code: Int, body: String): BridgeReconciliationStatus {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/payments/reconcile") { exchange ->
                    exchange.sendResponseHeaders(code, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                start()
            }
            return try {
                X402BridgeReconciliationClient(
                    URI("http://127.0.0.1:${server.address.port}"),
                    "local-test-bridge-secret",
                    jacksonObjectMapper()
                )
                    .reconcile(attempt()).status
            } finally {
                server.stop(0)
            }
        }

        assertThat(statusFor(400, "bad request")).isEqualTo(BridgeReconciliationStatus.DEFINITE_FAILURE)
        assertThat(statusFor(503, "down")).isEqualTo(BridgeReconciliationStatus.UNKNOWN)
        assertThat(statusFor(200, "not-json")).isEqualTo(BridgeReconciliationStatus.UNKNOWN)
    }

    @Test
    fun `oversized reconciliation response remains unknown`() {
        val body = "x".repeat(1_048_577)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/internal/payments/reconcile") { exchange ->
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        try {
            val result = X402BridgeReconciliationClient(
                URI("http://127.0.0.1:${server.address.port}"),
                "local-test-bridge-secret",
                jacksonObjectMapper()
            )
                .reconcile(attempt())
            assertThat(result.status).isEqualTo(BridgeReconciliationStatus.UNKNOWN)
        } finally {
            server.stop(0)
        }
    }

    private fun attempt(): PaymentAttempt {
        return PaymentAttempt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.X402
        )
    }
}
