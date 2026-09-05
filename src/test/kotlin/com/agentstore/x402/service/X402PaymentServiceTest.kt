package com.agentstore.x402.service

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.agentstore.x402.client.X402AgentClient
import com.agentstore.x402.codec.X402HeaderCodec
import com.agentstore.x402.dto.internal.X402ProviderVerificationRequestDto
import com.agentstore.x402.exception.ProviderCertificationRejectedException
import com.agentstore.x402.registry.X402PaymentCorrelationRegistry
import com.agentstore.x402.signer.X402Eip3009Signer
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class X402PaymentServiceTest {
    private companion object {
        const val PRIVATE_KEY = "0x1111111111111111111111111111111111111111111111111111111111111111"
        const val PAY_TO = "0x0000000000000000000000000000000000000001"
        const val TRANSACTION = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val DEFAULT_DEADLINE: Duration = Duration.ofSeconds(30)
    }

    private val objectMapper = jacksonObjectMapper()
    private val codec = X402HeaderCodec(objectMapper)

    @Test
    fun `successful receipt returns settled output`() {
        withAgent(paidStatus = 200, paidBody = """{"answer":42}""") { endpoint, calls ->
            val result = client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint))

            assertThat(result.transactionHash).isEqualTo(TRANSACTION)
            assertThat(result.output.path("answer").intValue()).isEqualTo(42)
            assertThat(calls).hasValue(2)
        }
    }

    @Test
    fun `provider certification classifies a settled non OK response as known paid failure`() {
        withAgent(paidStatus = 503, paidBody = "upstream unavailable") { endpoint, calls ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(ProviderCertificationRejectedException::class.java)
            assertThat(calls).hasValueGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `provider certification keeps a non OK response without receipt unknown`() {
        withAgent(paidStatus = 503, includeReceipt = false) { endpoint, _ ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(PaymentOutcomeUnknownException::class.java)
        }
    }

    @Test
    fun `provider certification keeps a successful response without receipt unknown`() {
        withAgent(paidStatus = 200, includeReceipt = false) { endpoint, _ ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(PaymentOutcomeUnknownException::class.java)
        }
    }

    @Test
    fun `provider certification rejects malformed terms before signing`() {
        withAgent(challenge = challenge(amount = "2")) { endpoint, calls ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(calls).hasValue(1)
        }
    }

    @Test
    fun `provider certification keeps an incomplete receipt unknown`() {
        val malformedReceipt = objectMapper.createObjectNode().apply {
            put("success", true)
            put("network", X402PaymentService.BASE_SEPOLIA)
        }
        withAgent(receipt = malformedReceipt) { endpoint, _ ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(PaymentOutcomeUnknownException::class.java)
        }
    }

    @Test
    fun `provider certification keeps an unreadable receipt unknown`() {
        withAgent(receiptHeader = "not-a-payment-receipt") { endpoint, _ ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(PaymentOutcomeUnknownException::class.java)
        }
    }

    @Test
    fun `provider certification keeps a wrong receipt network unknown`() {
        val receipt = receipt().apply {
            put("network", "eip155:1")
        }
        withAgent(receipt = receipt) { endpoint, _ ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(PaymentOutcomeUnknownException::class.java)
        }
    }

    @Test
    fun `provider certification timeout after signing remains unknown`() {
        withAgent(paidDelayMillis = 1_000) { endpoint, _ ->
            assertThatThrownBy {
                client(deadline = Duration.ofMillis(300)).certify(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(PaymentOutcomeUnknownException::class.java)
        }
    }

    @Test
    fun `provider certification wraps verification input in public invocation contract`() {
        val receivedBodies = Collections.synchronizedList(mutableListOf<String>())
        val input = objectMapper.readTree("""{"query":"base sepolia"}""")

        withAgent(receivedBodies = receivedBodies) { endpoint, calls ->
            client(deadline = DEFAULT_DEADLINE).certify(
                verificationRequest(endpoint = endpoint).copy(input = input),
            )

            assertThat(calls).hasValue(2)
            assertThat(receivedBodies).containsExactly(
                """{"input":{"query":"base sepolia"}}""",
                """{"input":{"query":"base sepolia"}}""",
            )
        }
    }

    @Test
    fun `provider preflight stops after the unsigned payment challenge`() {
        withAgent { endpoint, calls ->
            client(deadline = DEFAULT_DEADLINE).preflightProvider(verificationRequest(endpoint = endpoint))
            assertThat(calls).hasValue(1)
        }
    }

    @Test
    fun `provider preflight rejects malformed terms without signing`() {
        withAgent(challenge = challenge(amount = "2")) { endpoint, calls ->
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).preflightProvider(verificationRequest(endpoint = endpoint))
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(calls).hasValue(1)
        }
    }

    @Test
    fun `settled agent failure preserves receipt for caller terminalization`() {
        withAgent(paidStatus = 503, paidBody = "upstream unavailable") { endpoint, _ ->
            val result = client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint))

            assertThat(result.agentStatus).isEqualTo(503)
            assertThat(result.transactionHash).isEqualTo(TRANSACTION)
            assertThat(result.output.textValue()).isEqualTo("upstream unavailable")
        }
    }

    @Test
    fun `post signature ambiguity remains unknown`() {
        withAgent(includeReceipt = false) { endpoint, calls ->
            assertThatThrownBy { client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint)) }
                .isInstanceOf(PaymentOutcomeUnknownException::class.java)
            assertThat(calls).hasValue(2)
        }
    }

    @Test
    fun `paid request timeout remains unknown`() {
        withAgent(paidDelayMillis = 1_000) { endpoint, calls ->
            assertThatThrownBy {
                client(deadline = Duration.ofMillis(300)).invoke(request(endpoint = endpoint))
            }
                .isInstanceOf(PaymentOutcomeUnknownException::class.java)
            assertThat(calls).hasValue(2)
        }
    }

    @Test
    fun `slow paid response cannot extend the absolute invocation deadline`() {
        withAgent(paidChunkDelayMillis = 50) { endpoint, calls ->
            assertThatThrownBy {
                client(deadline = Duration.ofMillis(300)).invoke(request(endpoint = endpoint))
            }
                .isInstanceOf(PaymentOutcomeUnknownException::class.java)
            assertThat(calls).hasValue(2)
        }
    }

    @Test
    fun `challenge must exactly match quote before signing`() {
        withAgent(challenge = challenge(amount = "2")) { endpoint, calls ->
            assertThatThrownBy { client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .isNotInstanceOf(PaymentOutcomeUnknownException::class.java)
            assertThat(calls).hasValue(1)
        }
    }

    @Test
    fun `unsupported transfer method is not signed`() {
        withAgent(challenge = challenge(method = "permit2")) { endpoint, calls ->
            assertThatThrownBy { client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("unsupported_x402_asset_transfer_method")
            assertThat(calls).hasValue(1)
        }
    }

    @Test
    fun `supported EIP-3009 requirement is selected when Permit2 is listed first`() {
        val mixedChallenge: (String) -> ObjectNode = { endpoint ->
            challenge()(endpoint).also { root ->
                val supported = (root.withArray("accepts").first() as ObjectNode).deepCopy()
                val permit2 = supported.deepCopy().also {
                    (it.path("extra") as ObjectNode).put("assetTransferMethod", "permit2")
                }
                root.withArray("accepts").insert(0, permit2)
            }
        }
        withAgent(challenge = mixedChallenge) { endpoint, calls ->
            assertThat(client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint)).transactionHash).isEqualTo(TRANSACTION)
            assertThat(calls).hasValue(2)
        }
    }

    @Test
    fun `oversized request fails before the unpaid Agent request`() {
        val endpoint = "http://127.0.0.1:1/invoke"
        val request = request(endpoint).copy(body = mapOf("input" to "x".repeat(1_048_577)))

        assertThatThrownBy { client(deadline = DEFAULT_DEADLINE).invoke(request) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("agent_request_too_large")
    }

    @Test
    fun `non successful receipt remains unknown after signing`() {
        val receipt = objectMapper.createObjectNode().apply {
            put("success", false)
            put("transaction", "")
            put("network", X402PaymentService.BASE_SEPOLIA)
        }
        withAgent(receipt = receipt) { endpoint, calls ->
            assertThatThrownBy { client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint)) }
                .isInstanceOf(PaymentOutcomeUnknownException::class.java)
            assertThat(calls).hasValue(2)
        }
    }

    @Test
    fun `oversized unpaid response is rejected before signing`() {
        withAgent(unpaidBody = "x".repeat(1_048_577)) { endpoint, calls ->
            assertThatThrownBy { client(deadline = DEFAULT_DEADLINE).invoke(request(endpoint)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("agent_response_too_large")
            assertThat(calls).hasValue(1)
        }
    }

    @Test
    fun `redirect is not followed`() {
        val redirectedCalls = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/target") { exchange ->
                redirectedCalls.incrementAndGet()
                respond(exchange, 200, "{}")
            }
            start()
        }
        val redirect = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/invoke") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${target.address.port}/target"
                )
                respond(exchange, 302, "")
            }
            start()
        }
        try {
            assertThatThrownBy {
                client(deadline = DEFAULT_DEADLINE).invoke(request("http://127.0.0.1:${redirect.address.port}/invoke"))
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("x402_payment_required_response_missing")
            assertThat(redirectedCalls).hasValue(0)
        } finally {
            redirect.stop(0)
            target.stop(0)
        }
    }

    private fun client(deadline: Duration): X402PaymentService {
        val environment = MockEnvironment().apply { setActiveProfiles("test") }
        return X402PaymentService(
            X402AgentClient(
                AgentEndpointPolicy(environment) { error("loopback must not resolve DNS") },
                PinnedAgentRestClientFactory(),
            ),
            objectMapper,
            X402Eip3009Signer(
                privateKey = PRIVATE_KEY,
                objectMapper = objectMapper,
                clock = Clock.systemUTC(),
                secureRandom = SecureRandom(),
            ),
            X402PaymentCorrelationRegistry(),
            deadline,
        )
    }

    private fun request(endpoint: String): PaymentInvocationRequestDto {
        return PaymentInvocationRequestDto(
            paymentAttemptId = "attempt",
            idempotencyKey = "attempt",
            invocationToken = "token",
            endpoint = endpoint,
            amountAtomic = "1",
            maxPriceAtomic = "1",
            network = X402PaymentService.BASE_SEPOLIA,
            asset = X402PaymentService.BASE_SEPOLIA_USDC,
            payTo = PAY_TO,
            body = mapOf("input" to "test"),
        )
    }

    private fun verificationRequest(endpoint: String): X402ProviderVerificationRequestDto {
        return X402ProviderVerificationRequestDto(
            endpoint = endpoint,
            amountAtomic = "1",
            network = X402PaymentService.BASE_SEPOLIA,
            asset = X402PaymentService.BASE_SEPOLIA_USDC,
            payTo = PAY_TO,
            input = objectMapper.createObjectNode(),
        )
    }

    private fun challenge(
        amount: String = "1",
        method: String = "eip3009"
    ): (String) -> ObjectNode = { endpoint ->
        objectMapper.createObjectNode().apply {
            put("x402Version", 2)
            set<ObjectNode>("resource", objectMapper.createObjectNode().put("url", endpoint))
            set<ArrayNode>(
                "accepts",
                objectMapper.createArrayNode().add(
                    objectMapper.createObjectNode().apply {
                        put("scheme", "exact")
                        put("network", X402PaymentService.BASE_SEPOLIA)
                        put("amount", amount)
                        put("asset", X402PaymentService.BASE_SEPOLIA_USDC)
                        put("payTo", PAY_TO)
                        put("maxTimeoutSeconds", 60)
                        set<ObjectNode>("extra", objectMapper.createObjectNode().apply {
                            put("assetTransferMethod", method)
                            put("name", "USDC")
                            put("version", "2")
                        })
                    }
                ))
        }
    }

    private fun withAgent(
        challenge: (String) -> ObjectNode = challenge(),
        includeReceipt: Boolean = true,
        receipt: ObjectNode = receipt(),
        receiptHeader: String? = null,
        paidStatus: Int = 200,
        paidBody: String = "{}",
        unpaidBody: String = "",
        paidDelayMillis: Long = 0,
        paidChunkDelayMillis: Long = 0,
        receivedBodies: MutableList<String>? = null,
        assertion: (String, AtomicInteger) -> Unit,
    ) {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/invoke") { exchange ->
            exchange.requestBody.use { input ->
                receivedBodies?.add(input.readAllBytes().toString(Charsets.UTF_8))
            }
            calls.incrementAndGet()
            val endpoint = "http://127.0.0.1:${server.address.port}/invoke"
            if (exchange.requestHeaders.getFirst("PAYMENT-SIGNATURE") == null) {
                exchange.responseHeaders.add("PAYMENT-REQUIRED", codec.encode(challenge(endpoint)))
                respond(exchange, 402, unpaidBody)
            } else {
                if (paidDelayMillis > 0) Thread.sleep(paidDelayMillis)
                if (includeReceipt) {
                    exchange.responseHeaders.add(
                        "PAYMENT-RESPONSE",
                        receiptHeader ?: codec.encode(receipt),
                    )
                }
                if (paidChunkDelayMillis > 0) {
                    exchange.sendResponseHeaders(paidStatus, 0)
                    exchange.responseBody.use { output ->
                        repeat(20) {
                            output.write('x'.code)
                            output.flush()
                            Thread.sleep(paidChunkDelayMillis)
                        }
                    }
                    return@createContext
                }
                respond(exchange, paidStatus, paidBody)
            }
        }
        server.start()
        try {
            assertion("http://127.0.0.1:${server.address.port}/invoke", calls)
        } finally {
            server.stop(0)
        }
    }

    private fun receipt(): ObjectNode {
        return objectMapper.createObjectNode().apply {
            put("success", true)
            put("transaction", TRANSACTION)
            put("network", X402PaymentService.BASE_SEPOLIA)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

}
