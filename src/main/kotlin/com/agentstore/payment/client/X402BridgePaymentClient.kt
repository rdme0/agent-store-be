package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult
import com.agentstore.payment.dto.internal.X402BridgePaymentRequest
import com.agentstore.payment.dto.internal.X402BridgePaymentResponse
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.agentstore.payment.model.vo.PaymentMode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.*

class X402BridgePaymentClient(
    bridgeUrl: String,
    secret: String,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val bridgeUri: URI = validatedBridgeUri(bridgeUrl),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : PaymentClient {
    init {
        require(secret.length >= 16) { "X402_BRIDGE_SECRET must be at least 16 characters" }
    }

    override val mode = PaymentMode.X402
    private val signer = BridgeRequestSigner(secret)

    override fun invoke(request: PaymentInvocationRequest): PaymentInvocationResult {
        val payload = X402BridgePaymentRequest(
            request.paymentAttemptId,
            request.idempotencyKey,
            request.amountAtomic,
            request.maxPriceAtomic,
            request.network,
            request.asset,
            request.payTo,
            request.endpoint,
            headers = mapOf(
                "X-AgentStore-Invocation-Token" to request.invocationToken,
                "Idempotency-Key" to request.idempotencyKey
            ),
            body = objectMapper.writeValueAsString(request.body ?: emptyMap<String, Any>())
        )
        val bytes = objectMapper.writeValueAsBytes(payload)
        val timestamp = clock.millis().toString()
        val nonce = UUID.randomUUID().toString()
        val hash = signer.bodyHash(bytes)
        val requestMessage = HttpRequest.newBuilder(bridgeUri.resolve("/internal/payments/pay-and-invoke"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("X-AgentStore-Timestamp", timestamp).header("X-AgentStore-Nonce", nonce)
            .header("X-AgentStore-Body-Sha256", hash)
            .header(
                "X-AgentStore-Signature",
                signer.signature("POST", "/internal/payments/pay-and-invoke", timestamp, nonce, hash)
            )
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val raw = try {
            httpClient.send(requestMessage, HttpResponse.BodyHandlers.ofInputStream())
        } catch (exception: Exception) {
            throw PaymentOutcomeUnknownException("PAYMENT_RECONCILIATION_REQUIRED")
        }
        if (raw.statusCode() in 400..499) {
            throw IllegalStateException("bridge_http_${raw.statusCode()}")
        }
        if (raw.statusCode() !in 200..299) {
            throw PaymentOutcomeUnknownException("PAYMENT_RECONCILIATION_REQUIRED")
        }
        val response = try {
            objectMapper.readValue(readBounded(raw.body()), X402BridgePaymentResponse::class.java)
        } catch (exception: Exception) {
            throw PaymentOutcomeUnknownException("PAYMENT_RECONCILIATION_REQUIRED")
        }
        if (response.outcome == "UNKNOWN_AFTER_SIGNATURE") {
            throw PaymentOutcomeUnknownException(response.code ?: "PAYMENT_RECONCILIATION_REQUIRED")
        }
        if (response.outcome != "SETTLED" && response.outcome != "PAID_INVOCATION_FAILED") {
            throw IllegalStateException(response.code ?: response.outcome)
        }
        val output = objectMapper.readTree(response.response?.body ?: "null")
        return PaymentInvocationResult(
            output,
            response.transactionHash,
            response.paymentIdentifier,
            response.response?.status ?: 200
        )
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    return@use output.toByteArray()
                }
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw IllegalStateException("bridge_response_too_large")
                }
                output.write(buffer, 0, read)
            }
            error("unreachable")
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1_048_576

        fun validatedBridgeUri(value: String): URI {
            val uri =
                runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("X402_BRIDGE_URL must be a loopback HTTP URL") }
            require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.userInfo == null && uri.fragment == null) { "X402_BRIDGE_URL must use http://127.0.0.1" }
            return uri
        }
    }
}
