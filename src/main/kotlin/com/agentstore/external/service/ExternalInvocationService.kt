package com.agentstore.external.service

import com.agentstore.agent.service.FunctionContractService
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.security.dto.ExternalReceiptPrincipal
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.internal.ExecutionStartDto
import com.agentstore.execution.service.ExecutionService
import com.agentstore.external.config.ExternalInvocationProperties
import com.agentstore.external.dto.request.CreateExternalInvocationRequest
import com.agentstore.external.dto.internal.ExternalInvocationResultDto
import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import com.agentstore.external.dto.response.ExternalInvocationStatusResponse
import com.agentstore.external.model.entity.ExternalInvocation
import com.agentstore.external.repository.ExternalInvocationRepository
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
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class ExternalInvocationService(
    private val properties: ExternalInvocationProperties,
    private val agentStoreProperties: AgentStoreProperties,
    private val invocationRepository: ExternalInvocationRepository,
    private val functionContractService: FunctionContractService,
    private val quoteService: QuoteService,
    private val executionService: ExecutionService,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {
    companion object {
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
    }

    fun invoke(
        idempotencyKey: String?,
        request: CreateExternalInvocationRequest,
    ): ExternalInvocationResultDto {
        val normalizedKey = idempotencyKey?.trim()?.takeIf(IDEMPOTENCY_KEY::matches)
            ?: throw DomainClientException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED)
        val requestHash = hash(value = objectMapper.writeValueAsBytes(request))

        return transactionTemplate.execute {
            invocationRepository.acquireIdempotencyLock(idempotencyKey = normalizedKey)
            val existing = invocationRepository.findByIdempotencyKey(idempotencyKey = normalizedKey)
            if (existing != null) {
                if (existing.requestHash != requestHash) {
                    throw DomainClientException(ErrorCode.EXTERNAL_IDEMPOTENCY_CONFLICT)
                }
                return@execute accepted(invocation = existing)
            }

            val maximum = request.maxCostAtomic.toBigIntegerOrNull()
                ?: throw DomainClientException(ErrorCode.INVALID_BUDGET)
            if (maximum > properties.maxCostAtomic) {
                throw DomainClientException(ErrorCode.EXTERNAL_MAX_TOTAL_EXCEEDED)
            }

            val quote = createQuote(request = request, maximum = maximum)
            if (quote.maxCostAtomic.toBigInteger() > maximum) {
                throw DomainClientException(ErrorCode.EXTERNAL_MAX_TOTAL_EXCEEDED)
            }

            val snapshot = objectMapper.valueToTree<JsonNode>(quote.snapshot)
            validateInput(snapshot = snapshot, input = request.input, question = request.question)
            val input = request.input?.let { value -> objectMapper.valueToTree<JsonNode>(value) }
            val execution = executionService.create(
                start = ExecutionStartDto(
                    quoteId = quote.id,
                    maxBudgetAtomic = quote.maxCostAtomic.toBigInteger(),
                    question = request.question,
                    input = input,
                    allowExpiredQuote = false,
                ),
            )
            val id = UUID.randomUUID()
            val now = Instant.now(clock)
            val receiptToken = receiptToken(id = id, requestHash = requestHash)
            val invocation = invocationRepository.save(
                ExternalInvocation(
                    id,
                    execution.id,
                    normalizedKey,
                    requestHash,
                    hash(value = receiptToken.toByteArray(StandardCharsets.UTF_8)),
                    now.plus(properties.receiptTtl),
                ),
            )
            ExternalInvocationResultDto(
                invocationId = invocation.id,
                receiptToken = receiptToken,
                response = ExternalInvocationExecutionResponse(
                    id = invocation.id,
                    executionId = execution.id,
                    executionStatus = execution.status,
                    maxCostAtomic = execution.maxBudgetAtomic,
                ),
            )
        }
    }

    fun get(id: UUID, principal: ExternalReceiptPrincipal): ExternalInvocationStatusResponse {
        val invocation = authorizedInvocation(id = id, principal = principal)
        val execution = executionService.get(id = invocation.executionId)
        val output = execution.steps
            .firstOrNull { step -> step.parentStepId == null }
            ?.output
            ?.let { value -> objectMapper.valueToTree<JsonNode>(value) }
        return ExternalInvocationStatusResponse(
            id = invocation.id,
            executionId = execution.id,
            executionStatus = execution.status,
            output = output,
            maxCostAtomic = execution.maxBudgetAtomic,
            receiptExpiresAt = invocation.receiptExpiresAt,
        )
    }

    fun subscribe(id: UUID, principal: ExternalReceiptPrincipal, lastEventId: String?): SseEmitter {
        return executionService.subscribe(
            id = authorizedInvocation(id = id, principal = principal).executionId,
            lastEventId = lastEventId,
        )
    }

    private fun createQuote(
        request: CreateExternalInvocationRequest,
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
                val strategy = request.selectionStrategy
                    ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
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

    private fun authorizedInvocation(id: UUID, principal: ExternalReceiptPrincipal): ExternalInvocation {
        if (principal.invocationId != id || !principal.expiresAt.isAfter(Instant.now(clock))) {
            throw DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
        }
        return invocationRepository.findById(id).orElseThrow {
            DomainClientException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
        }
    }

    private fun validateInput(snapshot: JsonNode, input: JsonNode?, question: String?) {
        val context = objectMapper.createObjectNode()
        context.set<JsonNode>("input", input ?: objectMapper.nullNode())
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

    private fun accepted(invocation: ExternalInvocation): ExternalInvocationResultDto {
        val execution = executionService.get(id = invocation.executionId)
        return ExternalInvocationResultDto(
            invocationId = invocation.id,
            receiptToken = receiptToken(id = invocation.id, requestHash = invocation.requestHash),
            response = ExternalInvocationExecutionResponse(
                id = invocation.id,
                executionId = execution.id,
                executionStatus = execution.status,
                maxCostAtomic = execution.maxBudgetAtomic,
            ),
        )
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
