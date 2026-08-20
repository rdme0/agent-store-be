package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.BridgeReconciliationResult
import com.agentstore.payment.dto.internal.X402BridgeReconciliationRequest
import com.agentstore.payment.dto.internal.X402BridgeReconciliationResponse
import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.BridgeReconciliationStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.*

class X402BridgeReconciliationClient(
    private val bridgeUri: java.net.URI,
    secret: String,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : PaymentReconciliationClient {
    private val signer = BridgeRequestSigner(secret)

    override fun reconcile(attempt: PaymentAttempt): BridgeReconciliationResult {
        val payload = X402BridgeReconciliationRequest(
            attempt.id.toString(),
            attempt.id.toString(),
            attempt.transactionHash,
            attempt.paymentIdentifier
        )
        val bytes = objectMapper.writeValueAsBytes(payload)
        val timestamp = clock.millis().toString()
        val nonce = UUID.randomUUID().toString()
        val hash = signer.bodyHash(bytes)
        val request = HttpRequest.newBuilder(bridgeUri.resolve("/internal/payments/reconcile"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("X-AgentStore-Timestamp", timestamp).header("X-AgentStore-Nonce", nonce)
            .header("X-AgentStore-Body-Sha256", hash)
            .header(
                "X-AgentStore-Signature",
                signer.signature("POST", "/internal/payments/reconcile", timestamp, nonce, hash)
            )
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val raw = runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()) }.getOrNull()
            ?: return BridgeReconciliationResult(BridgeReconciliationStatus.UNKNOWN)
        if (raw.statusCode() in 400..499) {
            return BridgeReconciliationResult(BridgeReconciliationStatus.DEFINITE_FAILURE)
        }
        if (raw.statusCode() !in 200..299) {
            return BridgeReconciliationResult(BridgeReconciliationStatus.UNKNOWN)
        }
        val responseBytes = runCatching { readBounded(raw.body()) }.getOrNull()
            ?: return BridgeReconciliationResult(BridgeReconciliationStatus.UNKNOWN)
        val response = runCatching {
            objectMapper.readValue(
                responseBytes,
                X402BridgeReconciliationResponse::class.java
            )
        }.getOrNull()
            ?: return BridgeReconciliationResult(BridgeReconciliationStatus.UNKNOWN)
        if (response.status != "SETTLED" || response.transactionHash.isNullOrBlank()) {
            return BridgeReconciliationResult(BridgeReconciliationStatus.UNKNOWN)
        }
        return BridgeReconciliationResult(
            BridgeReconciliationStatus.SETTLED,
            response.transactionHash,
            response.paymentIdentifier
        )
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1_048_576
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
}
