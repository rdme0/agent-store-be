package com.agentstore.x402.service

import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.client.PaymentReconciliationClient
import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.payment.dto.internal.PaymentInvocationResultDto
import com.agentstore.payment.dto.internal.PaymentReconciliationResultDto
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.x402.client.X402AgentClient
import com.agentstore.x402.codec.X402HeaderCodec
import com.agentstore.x402.dto.internal.X402PaymentRequiredDto
import com.agentstore.x402.registry.X402PaymentCorrelationRegistry
import com.agentstore.x402.signer.X402Eip3009Signer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Locale

class X402PaymentService(
    private val agentClient: X402AgentClient,
    private val objectMapper: ObjectMapper,
    private val signer: X402Eip3009Signer,
    private val correlations: X402PaymentCorrelationRegistry,
    private val invocationDeadline: Duration,
) : PaymentClient, PaymentReconciliationClient {
    companion object {
        const val BASE_SEPOLIA = "eip155:84532"
        const val BASE_SEPOLIA_USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        private const val PAYMENT_REQUIRED = "PAYMENT-REQUIRED"
        private const val PAYMENT_RESPONSE = "PAYMENT-RESPONSE"
        private const val RECONCILIATION_REQUIRED = "PAYMENT_RECONCILIATION_REQUIRED"
        private const val MAX_AUTHORIZATION_SECONDS = 86_400L
        private val POSITIVE_ATOMIC = Regex("^[1-9][0-9]*$")
        private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
        private val EVM_TRANSACTION_HASH = Regex("^0x[0-9a-fA-F]{64}$")
    }

    override val mode = PaymentMode.X402
    private val headerCodec = X402HeaderCodec(objectMapper)

    override fun invoke(request: PaymentInvocationRequestDto): PaymentInvocationResultDto {
        val body = objectMapper.writeValueAsBytes(request.body ?: emptyMap<String, Any>())
        preflight(request = request, body = body)
        val fingerprint = fingerprint(request = request, body = body)
        return correlations.claim(
            paymentAttemptId = request.paymentAttemptId,
            idempotencyKey = request.idempotencyKey,
            fingerprint = fingerprint,
        ) {
            invokeOnce(request = request, body = body)
        }
    }

    override fun reconcile(attempt: PaymentAttempt): PaymentReconciliationResultDto {
        val key = attempt.id.toString()
        return correlations.reconcile(paymentAttemptId = key, idempotencyKey = key)
    }

    private fun invokeOnce(
        request: PaymentInvocationRequestDto,
        body: ByteArray
    ): PaymentInvocationResultDto {
        val deadline = System.nanoTime() + invocationDeadline.toNanos()
        val connection = agentClient.prepare(request.endpoint)
        val unpaid = agentClient.post(
            connection = connection,
            request = request,
            body = body,
            paymentSignature = null,
            deadline = deadline,
        )
        require(unpaid.status == 402) { "x402_payment_required_response_missing" }
        val requiredHeader = unpaid.headers.getFirst(PAYMENT_REQUIRED)
            ?: throw IllegalStateException("x402_payment_required_header_missing")
        val required = selectRequirement(
            root = headerCodec.decodeObject(value = requiredHeader),
            request = request,
        )
        val signatureHeader = headerCodec.encode(signer.createPaymentPayload(required))

        val paid = try {
            agentClient.post(
                connection = connection,
                request = request,
                body = body,
                paymentSignature = signatureHeader,
                deadline = deadline,
            )
        } catch (_: Exception) {
            throw PaymentOutcomeUnknownException(failureCode = RECONCILIATION_REQUIRED)
        }
        val receipt = try {
            val value = paid.headers.getFirst(PAYMENT_RESPONSE) ?: throw IllegalArgumentException()
            headerCodec.decodeReceipt(value)
        } catch (_: Exception) {
            throw PaymentOutcomeUnknownException(failureCode = RECONCILIATION_REQUIRED)
        }
        if (!receipt.success || receipt.network != BASE_SEPOLIA || !EVM_TRANSACTION_HASH.matches(
                receipt.transaction.orEmpty()
            )
        ) {
            throw PaymentOutcomeUnknownException(failureCode = RECONCILIATION_REQUIRED)
        }
        return PaymentInvocationResultDto(
            output = parseAgentOutput(paid.body),
            transactionHash = receipt.transaction,
            paymentIdentifier = receipt.transaction,
            agentStatus = paid.status,
        )
    }

    private fun selectRequirement(
        root: ObjectNode,
        request: PaymentInvocationRequestDto,
    ): X402PaymentRequiredDto {
        val versionNode = root.path("x402Version")
        require(versionNode.canConvertToInt() && versionNode.intValue() == 2) {
            "unsupported_x402_version"
        }

        val resource = root.path("resource").takeIf(JsonNode::isObject) as? ObjectNode
            ?: throw IllegalArgumentException("invalid_x402_resource")
        require(resource.path("url").takeIf(JsonNode::isTextual)?.textValue() == request.endpoint) {
            "x402_resource_mismatch"
        }

        val accepts = root.path("accepts").takeIf(JsonNode::isArray)
            ?: throw IllegalArgumentException("invalid_x402_requirements")
        val termMatches = accepts.filter { candidate ->
            baseTermsMatch(candidate = candidate, request = request)
        }
        require(termMatches.isNotEmpty()) { "x402_payment_terms_mismatch" }

        val matching = termMatches.firstOrNull { candidate ->
            val method = candidate.path("extra").path("assetTransferMethod")
            method.isMissingNode || method.isNull || (method.isTextual && method.textValue() == "eip3009")
        } as? ObjectNode ?: throw IllegalArgumentException("unsupported_x402_asset_transfer_method")

        val extra = matching.path("extra").takeIf(JsonNode::isObject) as? ObjectNode
            ?: throw IllegalArgumentException("x402_eip712_domain_missing")
        val method = extra.path("assetTransferMethod")
            .takeIf(JsonNode::isTextual)
            ?.textValue()
            ?: "eip3009"
        require(method == "eip3009") { "unsupported_x402_asset_transfer_method" }

        val name = extra.path("name")
            .takeIf(JsonNode::isTextual)
            ?.textValue()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("x402_eip712_domain_missing")
        val tokenVersion = extra.path("version")
            .takeIf(JsonNode::isTextual)
            ?.textValue()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("x402_eip712_domain_missing")
        val timeout = matching
            .path("maxTimeoutSeconds")
            .takeIf(JsonNode::canConvertToLong)
            ?.longValue()
            ?.takeIf { it in 1..MAX_AUTHORIZATION_SECONDS }
            ?: throw IllegalArgumentException("invalid_x402_timeout")
        val extensions = root.path("extensions").takeIf(JsonNode::isObject) as? ObjectNode

        return X402PaymentRequiredDto(
            resource = resource,
            selected = matching,
            maxTimeoutSeconds = timeout,
            tokenName = name,
            tokenVersion = tokenVersion,
            extensions = extensions,
        )
    }

    private fun baseTermsMatch(candidate: JsonNode, request: PaymentInvocationRequestDto): Boolean {
        return candidate.isObject &&
                candidate.path("scheme").textValue() == "exact" &&
                candidate.path("network").textValue() == BASE_SEPOLIA &&
                candidate.path("amount").textValue() == request.amountAtomic &&
                candidate.path("asset").textValue()
                    ?.equals(other = BASE_SEPOLIA_USDC, ignoreCase = true) == true &&
                candidate.path("asset").textValue()
                    ?.equals(other = request.asset, ignoreCase = true) == true &&
                candidate.path("payTo").textValue()
                    ?.equals(other = request.payTo, ignoreCase = true) == true
    }

    private fun preflight(request: PaymentInvocationRequestDto, body: ByteArray) {
        require(POSITIVE_ATOMIC.matches(request.amountAtomic)) { "invalid_payment_amount" }
        require(POSITIVE_ATOMIC.matches(request.maxPriceAtomic)) { "invalid_max_payment_amount" }
        require(BigInteger(request.amountAtomic) <= BigInteger(request.maxPriceAtomic)) { "payment_price_exceeded" }
        require(request.network == BASE_SEPOLIA) { "unsupported_x402_network" }
        require(
            request.asset.equals(
                other = BASE_SEPOLIA_USDC,
                ignoreCase = true,
            )
        ) { "unsupported_x402_asset" }
        require(EVM_ADDRESS.matches(request.payTo)) { "invalid_x402_payee" }
        require(body.size <= X402AgentClient.MAX_BODY_BYTES) { "agent_request_too_large" }
    }

    private fun fingerprint(request: PaymentInvocationRequestDto, body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            request.amountAtomic,
            request.maxPriceAtomic,
            request.network,
            request.asset.lowercase(Locale.ROOT),
            request.payTo.lowercase(Locale.ROOT),
            request.endpoint,
        ).forEach { value ->
            digest.update(value.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        digest.update(body)
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun parseAgentOutput(body: ByteArray): JsonNode {
        if (body.isEmpty()) {
            return NullNode.instance
        }

        return runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: TextNode(body.toString(StandardCharsets.UTF_8))
    }

}
