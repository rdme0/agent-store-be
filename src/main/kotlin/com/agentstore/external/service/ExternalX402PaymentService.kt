package com.agentstore.external.service

import com.agentstore.external.client.FacilitatorIncomingPaymentClient
import com.agentstore.external.config.ExternalApiProperties
import com.agentstore.external.dto.internal.ExternalPaymentRequirementDto
import com.agentstore.external.dto.internal.IncomingPaymentSettlementDto
import com.agentstore.external.dto.internal.IncomingPaymentVerificationDto
import com.agentstore.external.exception.ExternalIncomingPaymentRejectedException
import com.agentstore.external.exception.ExternalIncomingPaymentUnknownException
import com.agentstore.external.model.entity.ExternalInvocationIntent
import com.agentstore.x402.codec.X402HeaderCodec
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

class ExternalX402PaymentService(
    private val properties: ExternalApiProperties,
    private val facilitatorClient: FacilitatorIncomingPaymentClient,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val BASE_SEPOLIA = "eip155:84532"
        private const val BASE_SEPOLIA_USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        private const val PAYMENT_SIGNATURE = "PAYMENT-SIGNATURE"
        private const val PAYMENT_RESPONSE = "PAYMENT-RESPONSE"
        private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    }

    private val headerCodec = X402HeaderCodec(objectMapper)

    fun paymentRequired(intent: ExternalInvocationIntent): ExternalPaymentRequirementDto {
        val resourceUrl = properties.publicBaseUrl.trimEnd('/') + "/v1/invocations"
        val requirement = objectMapper.createObjectNode().apply {
            put("scheme", "exact")
            put("network", BASE_SEPOLIA)
            put("amount", intent.totalCostAtomic.toString())
            put("asset", BASE_SEPOLIA_USDC)
            put("payTo", intent.payTo)
            put("maxTimeoutSeconds", properties.authorizationTimeout.seconds)
            set<ObjectNode>("extra", objectMapper.createObjectNode().apply {
                put("assetTransferMethod", "eip3009")
                put("name", "USDC")
                put("version", "2")
            })
        }
        val root = objectMapper.createObjectNode().apply {
            put("x402Version", 2)
            set<ObjectNode>("resource", objectMapper.createObjectNode().apply {
                put("url", resourceUrl)
                put("description", "AgentStore external agent invocation")
                put("mimeType", "application/json")
            })
            set<JsonNode>("accepts", objectMapper.createArrayNode().add(requirement))
        }
        return ExternalPaymentRequirementDto(
            resourceUrl = resourceUrl,
            amountAtomic = intent.totalCostAtomic.toString(),
            payTo = intent.payTo,
            header = headerCodec.encode(value = root),
            requirement = requirement,
            expiresAt = intent.expiresAt,
        )
    }

    fun verify(
        signatureHeader: String,
        requirement: ExternalPaymentRequirementDto,
    ): IncomingPaymentVerificationDto {
        val paymentPayload = decodeAndValidate(
            signatureHeader = signatureHeader,
            requirement = requirement,
        )
        return facilitatorClient.verify(
            paymentPayload = paymentPayload,
            paymentRequirement = requirement.requirement,
        )
    }

    fun settle(
        signatureHeader: String,
        requirement: ExternalPaymentRequirementDto,
    ): IncomingPaymentSettlementDto {
        val paymentPayload = decodeAndValidate(
            signatureHeader = signatureHeader,
            requirement = requirement,
        )
        return facilitatorClient.settle(
            paymentPayload = paymentPayload,
            paymentRequirement = requirement.requirement,
        )
    }

    fun paymentFingerprint(signatureHeader: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signatureHeader.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    fun paymentResponse(settlement: IncomingPaymentSettlementDto): String {
        val receipt = objectMapper.createObjectNode().apply {
            put("success", true)
            put("network", BASE_SEPOLIA)
            put("payer", settlement.payer)
            put("transaction", settlement.transactionHash)
        }
        return headerCodec.encode(value = receipt)
    }

    private fun decodeAndValidate(
        signatureHeader: String,
        requirement: ExternalPaymentRequirementDto,
    ): ObjectNode {
        val root = try {
            headerCodec.decodeObject(value = signatureHeader)
        } catch (_: Exception) {
            throw ExternalIncomingPaymentRejectedException()
        }
        val accepted = root.path("accepted").takeIf(JsonNode::isObject) as? ObjectNode
            ?: throw ExternalIncomingPaymentRejectedException()
        val authorization = root.path("payload").path("authorization")
        val authorizationExpiresAt = authorization.path("validBefore").asText().toLongOrNull()
        if (
            root.path("x402Version").asInt() != 2 ||
            root.path("resource").path("url").asText() != requirement.resourceUrl ||
            accepted.path("scheme").asText() != "exact" ||
            accepted.path("network").asText() != BASE_SEPOLIA ||
            accepted.path("asset").asText().equals(BASE_SEPOLIA_USDC, ignoreCase = true).not() ||
            accepted.path("amount").asText() != requirement.amountAtomic ||
            accepted.path("payTo").asText().equals(requirement.payTo, ignoreCase = true).not() ||
            accepted.path("extra").path("assetTransferMethod").asText() != "eip3009" ||
            root.path("payload").path("signature").asText().isBlank() ||
            authorization.path("to").asText().equals(requirement.payTo, ignoreCase = true).not() ||
            authorization.path("value").asText() != requirement.amountAtomic ||
            authorization.path("from").asText().matches(EVM_ADDRESS).not() ||
            authorizationExpiresAt == null ||
            authorizationExpiresAt > requirement.expiresAt.epochSecond
        ) {
            throw ExternalIncomingPaymentRejectedException()
        }
        return root
    }
}
