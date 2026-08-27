package com.agentstore.external.service

import com.agentstore.agent.service.FunctionContractService
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.internal.ExecutionStartDto
import com.agentstore.execution.service.ExecutionService
import com.agentstore.external.config.ExternalApiProperties
import com.agentstore.external.dto.request.CreateExternalInvocationIntentRequest
import com.agentstore.external.dto.internal.ExternalInvocationExecuteResultDto
import com.agentstore.external.dto.internal.ExternalInvocationIntentCreatedDto
import com.agentstore.external.dto.internal.ExternalInvocationResultDto
import com.agentstore.external.dto.internal.IncomingPaymentSettlementDto
import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import com.agentstore.external.dto.response.ExternalInvocationIntentResponse
import com.agentstore.external.dto.response.ExternalInvocationStatusResponse
import com.agentstore.external.exception.ExternalIncomingPaymentRejectedException
import com.agentstore.external.exception.ExternalIncomingPaymentUnknownException
import com.agentstore.external.model.entity.ExternalApiSale
import com.agentstore.external.model.entity.ExternalInvocationIntent
import com.agentstore.external.model.vo.ExternalInvocationStatus
import com.agentstore.external.repository.ExternalApiSaleRepository
import com.agentstore.external.repository.ExternalInvocationIntentRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class ExternalInvocationService(
    private val properties: ExternalApiProperties,
    private val agentStoreProperties: AgentStoreProperties,
    private val intentRepository: ExternalInvocationIntentRepository,
    private val saleRepository: ExternalApiSaleRepository,
    private val quoteService: QuoteService,
    private val executionService: ExecutionService,
    private val functionContractService: FunctionContractService,
    private val x402PaymentService: ExternalX402PaymentService,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {
    companion object {
        private const val FEE_DENOMINATOR = 10_000L
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
    }

    fun createIntent(
        idempotencyKey: String?,
        request: CreateExternalInvocationIntentRequest,
    ): ExternalInvocationIntentCreatedDto {
        val normalizedKey = idempotencyKey?.trim()?.takeIf(IDEMPOTENCY_KEY::matches)
            ?: throw DomainClientException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED)
        val requestHash = hash(value = objectMapper.writeValueAsBytes(request))

        return transactionTemplate.execute {
            intentRepository.acquireIdempotencyLock(normalizedKey)
            val existing = intentRepository.findByIdempotencyKey(normalizedKey)
            if (existing != null) {
                if (existing.requestHash != requestHash) {
                    throw DomainClientException(ErrorCode.EXTERNAL_IDEMPOTENCY_CONFLICT)
                }
                return@execute created(intent = existing, requestHash = requestHash)
            }

            val maximum = request.maxTotalAtomic.toBigIntegerOrNull()
                ?: throw DomainClientException(ErrorCode.INVALID_BUDGET)
            val quote = createQuote(request = request, maximum = maximum)
            val snapshot = objectMapper.valueToTree<JsonNode>(quote.snapshot)
            validateInput(snapshot = snapshot, input = request.input, question = request.question)
            val providerCost = quote.maxCostAtomic.toBigInteger()
            val fee = fee(providerCost = providerCost)
            val total = providerCost + fee
            if (total > maximum) {
                throw DomainClientException(ErrorCode.EXTERNAL_MAX_TOTAL_EXCEEDED)
            }
            val now = Instant.now(clock)
            val id = UUID.randomUUID()
            val input = request.input?.let { value -> objectMapper.valueToTree<JsonNode>(value) }
            val intent = ExternalInvocationIntent(
                id,
                quote.id,
                normalizedKey,
                requestHash,
                hash(value = receiptToken(id = id, requestHash = requestHash).toByteArray(StandardCharsets.UTF_8)),
                now.plus(properties.receiptTtl),
                providerCost,
                fee,
                total,
                properties.payTo,
                request.question,
                input,
                minOf(
                    a = quote.expiresAt,
                    b = now.plus(properties.intentTtl),
                ),
            )
            intentRepository.save(intent)

            created(intent = intent, requestHash = requestHash)
        }
    }

    fun invoke(
        idempotencyKey: String?,
        request: CreateExternalInvocationIntentRequest,
        signatureHeader: String?,
    ): ExternalInvocationResultDto {
        val created = createIntent(idempotencyKey = idempotencyKey, request = request)
        val result = execute(
            id = created.response.id,
            receiptToken = created.receiptToken,
            signatureHeader = signatureHeader,
        )
        return ExternalInvocationResultDto(
            invocationId = created.response.id,
            receiptToken = created.receiptToken,
            paymentRequiredHeader = result.paymentRequiredHeader,
            paymentResponseHeader = result.paymentResponseHeader,
            response = result.response,
        )
    }

    fun execute(
        id: UUID,
        receiptToken: String?,
        signatureHeader: String?,
    ): ExternalInvocationExecuteResultDto {
        val intent = authorize(id = id, receiptToken = receiptToken)

        when (intent.status) {
            ExternalInvocationStatus.EXECUTION_CREATED -> return accepted(intent = intent)
            ExternalInvocationStatus.SETTLED -> return createExecution(intentId = intent.id)
            ExternalInvocationStatus.SETTLING,
            ExternalInvocationStatus.RECONCILIATION_REQUIRED -> {
                throw DomainClientException(ErrorCode.EXTERNAL_PAYMENT_RECONCILIATION_REQUIRED)
            }
            ExternalInvocationStatus.FAILED -> throw DomainClientException(ErrorCode.EXTERNAL_PAYMENT_REQUIRED)
            ExternalInvocationStatus.PAYMENT_PENDING -> Unit
        }

        if (!intent.expiresAt.isAfter(Instant.now(clock))) {
            throw DomainClientException(ErrorCode.QUOTE_EXPIRED)
        }

        val requirement = x402PaymentService.paymentRequired(intent)
        if (signatureHeader.isNullOrBlank()) {
            return ExternalInvocationExecuteResultDto(
                paymentRequiredHeader = requirement.header,
                paymentResponseHeader = null,
                response = null,
            )
        }
        try {
            x402PaymentService.verify(
                signatureHeader = signatureHeader,
                requirement = requirement,
            )
        } catch (_: ExternalIncomingPaymentRejectedException) {
            return ExternalInvocationExecuteResultDto(
                paymentRequiredHeader = requirement.header,
                paymentResponseHeader = null,
                response = null,
            )
        }
        if (!claimSettlement(intentId = intent.id, signatureHeader = signatureHeader)) {
            return execute(id = id, receiptToken = receiptToken, signatureHeader = null)
        }
        val settlement = try {
            x402PaymentService.settle(
                signatureHeader = signatureHeader,
                requirement = requirement,
            )
        } catch (_: ExternalIncomingPaymentRejectedException) {
            failSettlement(intentId = intent.id)
            return ExternalInvocationExecuteResultDto(
                paymentRequiredHeader = requirement.header,
                paymentResponseHeader = null,
                response = null,
            )
        } catch (_: ExternalIncomingPaymentUnknownException) {
            markReconciliationRequired(intentId = intent.id)
            throw DomainClientException(ErrorCode.EXTERNAL_PAYMENT_RECONCILIATION_REQUIRED)
        }
        recordSettlement(intentId = intent.id, settlement = settlement)
        return createExecution(intentId = intent.id)
    }

    fun get(id: UUID, receiptToken: String?): ExternalInvocationStatusResponse {
        val intent = authorize(id = id, receiptToken = receiptToken)
        val execution = intent.executionId?.let { executionId -> executionService.get(id = executionId) }
        val output = execution?.steps
            ?.firstOrNull { step -> step.parentStepId == null }
            ?.output
            ?.let { value -> objectMapper.valueToTree<JsonNode>(value) }
        return ExternalInvocationStatusResponse(
            id = intent.id,
            status = intent.status,
            executionId = intent.executionId,
            executionStatus = execution?.status,
            output = output,
            providerCostAtomic = intent.providerCostAtomic.toString(),
            platformFeeAtomic = intent.platformFeeAtomic.toString(),
            totalCostAtomic = intent.totalCostAtomic.toString(),
            expiresAt = intent.expiresAt,
        )
    }

    fun executionId(id: UUID, receiptToken: String?): UUID {
        val intent = authorize(id = id, receiptToken = receiptToken)
        return intent.executionId ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
    }

    fun subscribe(id: UUID, receiptToken: String?, lastEventId: String?): SseEmitter {
        return executionService.subscribe(
            id = executionId(id = id, receiptToken = receiptToken),
            lastEventId = lastEventId,
        )
    }

    private fun createQuote(
        request: CreateExternalInvocationIntentRequest,
        maximum: BigInteger,
    ): QuoteResponse {
        val agentCode = request.agentCode?.trim()?.takeIf(String::isNotBlank)
        val functionCode = request.functionCode?.trim()?.takeIf(String::isNotBlank)

        return when {
            agentCode != null && functionCode == null -> {
                val constraint = request.versionConstraint?.trim()?.takeIf(String::isNotBlank)
                    ?: throw DomainClientException(ErrorCode.INVALID_VERSION_CONSTRAINT)
                if (request.contractVersion != null || request.selectionStrategy != null) {
                    throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                }
                quoteService.create(
                    code = agentCode,
                    request = QuoteRequest(versionConstraint = constraint),
                )
            }
            agentCode == null && functionCode != null -> {
                if (request.versionConstraint != null) {
                    throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                }
                val contractVersion = request.contractVersion?.trim()?.takeIf(String::isNotBlank)
                    ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                val strategy = request.selectionStrategy ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                quoteService.createFunction(
                    functionCode = functionCode,
                    contractVersion = contractVersion,
                    strategy = strategy,
                    maxTotalAtomic = maximum,
                )
            }
            else -> throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun validateInput(snapshot: JsonNode, input: Any?, question: String?) {
        val context = objectMapper.createObjectNode()
        val inputValue = input?.let { value -> objectMapper.valueToTree<JsonNode>(value) }
            ?: objectMapper.nullNode()
        context.set<JsonNode>("input", inputValue)
        question?.let { value -> context.put("question", value) }
        val schema = snapshot.path("version").path("functionContract").path("inputSchema")
        if (schema.isObject) {
            functionContractService.validateInstance(
                schema = schema,
                value = context,
                errorCode = ErrorCode.AGENT_INPUT_SCHEMA_INVALID,
            )
        }
    }

    private fun claimSettlement(intentId: UUID, signatureHeader: String): Boolean {
        return transactionTemplate.execute {
            val intent = intentRepository.findByIdForUpdate(intentId)
                ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)

            when (intent.status) {
                ExternalInvocationStatus.PAYMENT_PENDING -> {
                    intent.markSettling(x402PaymentService.paymentFingerprint(signatureHeader = signatureHeader))
                    intentRepository.save(intent)
                    true
                }
                else -> false
            }
        }
    }

    private fun recordSettlement(intentId: UUID, settlement: IncomingPaymentSettlementDto) {
        transactionTemplate.execute {
            val intent = intentRepository.findByIdForUpdate(intentId)
                ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)

            when (intent.status) {
                ExternalInvocationStatus.SETTLING -> {
                    intent.markSettled(
                        settlement.payer,
                        settlement.transactionHash,
                    )
                    intentRepository.save(intent)

                    if (!saleRepository.existsByExternalIntentId(intent.id)) {
                        saleRepository.save(
                            ExternalApiSale(
                                UUID.randomUUID(),
                                intent.id,
                                intent.providerCostAtomic,
                                intent.platformFeeAtomic,
                                intent.totalCostAtomic,
                                settlement.payer,
                                settlement.transactionHash,
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun createExecution(intentId: UUID): ExternalInvocationExecuteResultDto {
        return transactionTemplate.execute {
            val intent = intentRepository.findByIdForUpdate(intentId)
                ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)

            when (intent.status) {
                ExternalInvocationStatus.EXECUTION_CREATED -> accepted(intent = intent)
                ExternalInvocationStatus.SETTLED -> {
                    val quote = quoteService.requireQuote(intent.quoteId)
                    val execution = executionService.create(
                        start = ExecutionStartDto(
                            quoteId = quote.id,
                            maxBudgetAtomic = quote.maxCostAtomic,
                            question = intent.question,
                            input = intent.input,
                            allowExpiredQuote = true,
                        ),
                    )
                    intent.markExecutionCreated(execution.id)
                    intentRepository.save(intent)

                    accepted(intent = intent)
                }
                else -> throw DomainClientException(ErrorCode.EXTERNAL_PAYMENT_RECONCILIATION_REQUIRED)
            }
        }
    }

    private fun accepted(intent: ExternalInvocationIntent): ExternalInvocationExecuteResultDto {
        val executionId = intent.executionId
            ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
        val settlement = IncomingPaymentSettlementDto(
            payer = intent.payer ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND),
            transactionHash = intent.transactionHash
                ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND),
        )
        return ExternalInvocationExecuteResultDto(
            paymentRequiredHeader = null,
            paymentResponseHeader = x402PaymentService.paymentResponse(settlement = settlement),
            response = ExternalInvocationExecutionResponse(
                id = intent.id,
                status = intent.status,
                executionId = executionId,
                totalCostAtomic = intent.totalCostAtomic.toString(),
            ),
        )
    }

    private fun markReconciliationRequired(intentId: UUID) {
        transactionTemplate.execute {
            val intent = intentRepository.findByIdForUpdate(intentId) ?: return@execute
            intent.markReconciliationRequired("EXTERNAL_SETTLEMENT_UNKNOWN")
            intentRepository.save(intent)
        }
    }

    private fun failSettlement(intentId: UUID) {
        transactionTemplate.execute {
            val intent = intentRepository.findByIdForUpdate(intentId) ?: return@execute
            intent.markReconciliationRequired("EXTERNAL_SETTLEMENT_REJECTED")
            intentRepository.save(intent)
        }
    }

    private fun authorize(id: UUID, receiptToken: String?): ExternalInvocationIntent {
        val intent = intentRepository.findById(id).orElseThrow {
            DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
        }
        val token = receiptToken?.trim().takeUnless(String?::isNullOrBlank)
            ?: throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
        val expired = Instant.now(clock).isAfter(intent.receiptExpiresAt)
        val suppliedTokenHash = hash(value = token.toByteArray(StandardCharsets.UTF_8))
        val tokenMatches = MessageDigest.isEqual(
            suppliedTokenHash.toByteArray(StandardCharsets.UTF_8),
            intent.receiptTokenHash.toByteArray(StandardCharsets.UTF_8),
        )
        if (expired || !tokenMatches) {
            throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
        }
        return intent
    }

    private fun created(
        intent: ExternalInvocationIntent,
        requestHash: String,
    ): ExternalInvocationIntentCreatedDto {
        val requirement = x402PaymentService.paymentRequired(intent = intent)
        return ExternalInvocationIntentCreatedDto(
            response = ExternalInvocationIntentResponse(
                id = intent.id,
                executeUrl = requirement.resourceUrl,
                providerCostAtomic = intent.providerCostAtomic.toString(),
                platformFeeAtomic = intent.platformFeeAtomic.toString(),
                totalCostAtomic = intent.totalCostAtomic.toString(),
                expiresAt = intent.expiresAt,
            ),
            receiptToken = receiptToken(id = intent.id, requestHash = requestHash),
        )
    }

    private fun fee(providerCost: BigInteger): BigInteger {
        val basisPoints = BigInteger.valueOf(properties.feeBasisPoints.toLong())
        val denominator = BigInteger.valueOf(FEE_DENOMINATOR)
        return providerCost.multiply(basisPoints).add(denominator - BigInteger.ONE).divide(denominator)
    }

    private fun receiptToken(id: UUID, requestHash: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secret = agentStoreProperties.runtimeTokenSecret.toByteArray(StandardCharsets.UTF_8)
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val signed = mac.doFinal("external-receipt:$id:$requestHash".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signed)
    }

    private fun hash(value: ByteArray): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
    }

}
