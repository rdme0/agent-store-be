package com.agentstore.external.client

import com.agentstore.external.dto.internal.IncomingPaymentSettlementDto
import com.agentstore.external.dto.internal.IncomingPaymentVerificationDto
import com.agentstore.external.exception.ExternalIncomingPaymentRejectedException
import com.agentstore.external.exception.ExternalIncomingPaymentUnknownException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface FacilitatorIncomingPaymentGateway {
    fun verify(
        paymentPayload: ObjectNode,
        paymentRequirement: ObjectNode,
    ): IncomingPaymentVerificationDto

    fun settle(
        paymentPayload: ObjectNode,
        paymentRequirement: ObjectNode,
    ): IncomingPaymentSettlementDto
}

class FacilitatorIncomingPaymentClient(
    private val facilitatorBaseUri: URI,
    private val requestTimeout: Duration,
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper,
) : FacilitatorIncomingPaymentGateway {
    companion object {
        private const val MAX_RESPONSE_BYTES = 1_048_576
        private const val BASE_SEPOLIA = "eip155:84532"
        private val TRANSACTION_HASH = Regex("^0x[0-9a-fA-F]{64}$")
        private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    }

    override fun verify(
        paymentPayload: ObjectNode,
        paymentRequirement: ObjectNode,
    ): IncomingPaymentVerificationDto {
        val response = post(
            path = "verify",
            body = requestBody(
                paymentPayload = paymentPayload,
                paymentRequirement = paymentRequirement,
            ),
        )
        if (!response.path("isValid").asBoolean(false)) {
            throw ExternalIncomingPaymentRejectedException()
        }
        val payer = response.path("payer").asText().takeIf(EVM_ADDRESS::matches)
            ?: throw ExternalIncomingPaymentRejectedException()
        return IncomingPaymentVerificationDto(payer = payer)
    }

    override fun settle(
        paymentPayload: ObjectNode,
        paymentRequirement: ObjectNode,
    ): IncomingPaymentSettlementDto {
        val response = post(
            path = "settle",
            body = requestBody(
                paymentPayload = paymentPayload,
                paymentRequirement = paymentRequirement,
            ),
        )
        if (!response.path("success").asBoolean(false)) {
            throw ExternalIncomingPaymentRejectedException()
        }
        val transactionHash = response.path("transaction").asText().takeIf(TRANSACTION_HASH::matches)
            ?: throw ExternalIncomingPaymentUnknownException()
        val network = response.path("network").asText()
        if (network != BASE_SEPOLIA) {
            throw ExternalIncomingPaymentUnknownException()
        }
        val payer = response.path("payer").asText().takeIf(EVM_ADDRESS::matches)
            ?: throw ExternalIncomingPaymentUnknownException()
        return IncomingPaymentSettlementDto(payer = payer, transactionHash = transactionHash)
    }

    private fun requestBody(
        paymentPayload: ObjectNode,
        paymentRequirement: ObjectNode,
    ): ByteArray {
        val root = objectMapper.createObjectNode()
        root.put("x402Version", 2)
        root.set<ObjectNode>("paymentPayload", paymentPayload)
        root.set<ObjectNode>("paymentRequirements", paymentRequirement)
        return objectMapper.writeValueAsBytes(root)
    }

    private fun post(path: String, body: ByteArray): JsonNode {
        val request = HttpRequest.newBuilder(facilitatorBaseUri.resolve(path))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (_: Exception) {
            throw ExternalIncomingPaymentUnknownException()
        }
        if (response.statusCode() !in 200..299 || response.body().size > MAX_RESPONSE_BYTES) {
            throw ExternalIncomingPaymentUnknownException()
        }
        return try {
            objectMapper.readTree(response.body())
        } catch (_: Exception) {
            throw ExternalIncomingPaymentUnknownException()
        }
    }
}
