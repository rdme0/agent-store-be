package com.agentstore.external

import com.agentstore.external.client.FacilitatorIncomingPaymentClient
import com.agentstore.external.exception.ExternalIncomingPaymentRejectedException
import com.agentstore.external.exception.ExternalIncomingPaymentUnknownException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FacilitatorIncomingPaymentClientTest {
    @Test
    fun `facilitator verify rejection remains a definite pre-settlement failure`() {
        withServer(path = "/verify", response = "{\"isValid\":false}") { baseUri ->
            val client = client(baseUri = baseUri)

            assertThrows(ExternalIncomingPaymentRejectedException::class.java) {
                client.verify(paymentPayload = paymentPayload(), paymentRequirement = paymentRequirement())
            }
        }
    }

    @Test
    fun `missing settlement receipt becomes reconciliation required`() {
        withServer(
            path = "/settle",
            response = """
                {"success":true,"network":"eip155:84532","payer":"0x0000000000000000000000000000000000000001"}
            """.trimIndent(),
        ) { baseUri ->
            val client = client(baseUri = baseUri)

            assertThrows(ExternalIncomingPaymentUnknownException::class.java) {
                client.settle(paymentPayload = paymentPayload(), paymentRequirement = paymentRequirement())
            }
        }
    }

    private fun client(baseUri: URI): FacilitatorIncomingPaymentClient {
        return FacilitatorIncomingPaymentClient(
            facilitatorBaseUri = baseUri,
            requestTimeout = Duration.ofSeconds(2),
            httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            objectMapper = jacksonObjectMapper(),
        )
    }

    private fun paymentPayload(): ObjectNode {
        return jacksonObjectMapper().createObjectNode().put("signature", "signature")
    }

    private fun paymentRequirement(): ObjectNode {
        return jacksonObjectMapper().createObjectNode().put("scheme", "exact")
    }

    private fun withServer(path: String, response: String, action: (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { body -> body.write(bytes) }
        }
        server.start()

        try {
            action(URI("http://127.0.0.1:${server.address.port}/"))
        } finally {
            server.stop(0)
        }
    }
}
