package com.agentstore.execution.service

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.service.FunctionContractService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.service.QuoteService
import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.execution.dto.request.CreateExecutionRequest
import com.agentstore.execution.dto.internal.ExecutionStartDto
import com.agentstore.execution.dto.response.ExecutionEventResponse
import com.agentstore.execution.dto.response.ExecutionResponse
import com.agentstore.execution.dto.response.ExecutionStepResponse
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.exception.ExecutionNotFoundException
import com.agentstore.execution.guard.ExecutionMutationReadiness
import com.agentstore.execution.model.entity.Execution
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.runner.ExecutionRunner
import com.agentstore.payment.service.PaymentService
import com.agentstore.payment.dto.internal.KrwEstimateDto
import com.agentstore.payment.dto.response.KrwEstimateResponse
import com.agentstore.payment.service.KrwEstimateService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class ExecutionService(
    private val executionRepository: ExecutionRepository,
    private val executionStepRepository: ExecutionStepRepository,
    private val paymentService: PaymentService,
    private val quoteService: QuoteService,
    private val eventService: ExecutionEventService,
    private val objectMapper: ObjectMapper,
    private val runner: ExecutionRunner,
    private val mutationReadiness: ExecutionMutationReadiness,
    private val krwEstimateService: KrwEstimateService,
    private val functionContractService: FunctionContractService,
) {
    @Transactional
    fun create(request: CreateExecutionRequest): ExecutionResponse {
        val input = request.input?.let { value ->
            objectMapper.valueToTree<JsonNode>(value)
        }
        val budget = request.maxBudgetAtomic.toBigIntegerOrNull() ?: throw DomainClientException(
            ErrorCode.INVALID_BUDGET
        )
        return create(
            start = ExecutionStartDto(
                quoteId = request.quoteId,
                maxBudgetAtomic = budget,
                question = request.question,
                input = input,
                allowExpiredQuote = false,
            ),
        )
    }

    @Transactional
    fun create(start: ExecutionStartDto): ExecutionResponse {
        mutationReadiness.requireReady()
        val quote = quoteService.requireQuote(start.quoteId)
        val quoteExpired = !quote.expiresAt.isAfter(Instant.now())
        if (quoteExpired && !start.allowExpiredQuote) {
            throw DomainClientException(ErrorCode.QUOTE_EXPIRED)
        }
        if (start.maxBudgetAtomic != quote.maxCostAtomic) {
            throw DomainClientException(ErrorCode.BUDGET_MISMATCH)
        }
        val snapshot = quoteService.snapshot(quote)
        validateRootInput(
            snapshot = snapshot,
            input = invocationInput(input = start.input, question = start.question),
        )
        val execution =
            executionRepository.save(
                Execution(
                    UUID.randomUUID(),
                    quote.id,
                    start.maxBudgetAtomic,
                    start.question,
                    start.input,
                )
        )
        val rootVersionId =
            snapshot.path("version").path("id").asText().takeIf { it.isNotBlank() }
                ?.let(UUID::fromString)
                ?: quote.rootVersionId
        val rootStep = executionStepRepository.save(
            ExecutionStep(
                UUID.randomUUID(),
                execution.id,
                null,
                rootVersionId,
                objectMapper.valueToTree(
                    listOf(
                        snapshot.path("version").path("agentCode").asText()
                    )
                )
            )
        )
        eventService.append(
            executionId = execution.id,
            type = "EXECUTION_CREATED",
            payload = mapOf("quoteId" to quote.id, "maxBudgetAtomic" to start.maxBudgetAtomic.toString()),
        )
        val executionId = execution.id
        TransactionSynchronizationManager.registerSynchronization(object :
            TransactionSynchronization {
            override fun afterCommit() {
                runner.start(executionId)
            }
        })
        return toResponse(execution = execution, steps = listOf(rootStep))
    }

    @Transactional
    fun get(id: UUID): ExecutionResponse {
        val execution = executionRepository.findById(id)
            .orElseThrow { ExecutionNotFoundException() }
        return toResponse(
            execution = execution,
            steps = executionStepRepository.findAllByExecutionIdOrderByCreatedAtAsc(id),
        )
    }

    @Transactional
    fun events(
        id: UUID,
        afterSequence: Int
    ): List<ExecutionEventResponse> {
        if (!executionRepository.existsById(id)) {
            throw ExecutionNotFoundException()
        }
        return eventService.replay(executionId = id, afterSequence = afterSequence)
    }

    fun subscribe(id: UUID, lastEventId: String?): SseEmitter {
        if (!executionRepository.existsById(id)) {
            throw ExecutionNotFoundException()
        }
        return eventService.subscribe(
            executionId = id,
            afterSequence = lastEventId?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun jsonValue(value: JsonNode?): Any? {
        return value?.let { objectMapper.convertValue(it, Any::class.java) }
    }

    private fun toResponse(execution: Execution, steps: List<ExecutionStep>): ExecutionResponse {
        val snapshot = quoteService.snapshot(execution.quoteId)
        val stepPresentation = stepPresentation(snapshot)
        val quoteEstimate = runCatching {
            objectMapper.treeToValue(snapshot.path("krwEstimate"), KrwEstimateDto::class.java)
        }.getOrNull()
        val typedSnapshot = objectMapper.treeToValue(snapshot, QuoteSnapshotDto::class.java)
        return ExecutionResponse(
            id = execution.id,
            quoteId = execution.quoteId,
            quoteSnapshot = typedSnapshot,
            status = execution.status.name,
            maxBudgetAtomic = execution.maxBudgetAtomic.toString(),
            maxBudgetKrwEstimate = quoteEstimate
                ?.let { estimate ->
                    krwEstimateService.estimateAtRate(
                        amountAtomic = execution.maxBudgetAtomic,
                        estimate = estimate,
                    )
                }
                ?.let(KrwEstimateResponse::from),
            reservedCostAtomic = execution.reservedCostAtomic.toString(),
            actualCostAtomic = execution.actualCostAtomic.toString(),
            actualCostKrwEstimate = quoteEstimate
                ?.let { estimate ->
                    krwEstimateService.estimateAtRate(
                        amountAtomic = execution.actualCostAtomic,
                        estimate = estimate,
                    )
                }
                ?.let(KrwEstimateResponse::from),
            question = execution.question,
            input = jsonValue(value = execution.input),
            failureCode = execution.failureCode,
            steps = steps.map { step ->
                ExecutionStepResponse.from(
                    step = step,
                    payments = paymentService.findAllByStepId(step.id),
                    output = jsonValue(value = step.output),
                    responseFormat = stepPresentation[step.agentVersionId]?.responseFormat ?: AgentResponseFormat.JSON,
                    agentCode = stepPresentation[step.agentVersionId]?.agentCode,
                    agentName = stepPresentation[step.agentVersionId]?.agentName,
                )
            },
            createdAt = execution.createdAt,
            updatedAt = execution.updatedAt,
        )
    }

    private fun stepPresentation(snapshot: JsonNode): Map<UUID, StepPresentation> {
        val result = mutableMapOf<UUID, StepPresentation>()

        fun visit(node: JsonNode) {
            val version = node.path("version")
            val id = version.path("id").asText().takeIf { it.isNotBlank() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id != null) {
                result[id] = StepPresentation(
                    responseFormat = runCatching {
                        AgentResponseFormat.valueOf(version.path("responseFormat").asText())
                    }.getOrDefault(AgentResponseFormat.JSON),
                    agentCode = version.path("agentCode").asText().takeIf { value -> value.isNotBlank() },
                    agentName = version.path("agentName").asText().takeIf { value -> value.isNotBlank() },
                )
            }
            node.path("dependencies").forEach { dependency ->
                val resolved = dependency.path("resolved")
                if (resolved.isObject) visit(resolved)
            }
        }

        visit(snapshot)
        return result
    }

    private fun invocationInput(input: JsonNode?, question: String?): JsonNode {
        val context = objectMapper.createObjectNode()
        context.set<JsonNode>("input", input ?: objectMapper.nullNode())
        question?.let { value -> context.put("question", value) }
        return context
    }

    private fun validateRootInput(snapshot: JsonNode, input: JsonNode) {
        val schema = snapshot.path("version").path("functionContract").path("inputSchema")
        if (!schema.isObject) {
            return
        }
        functionContractService.validateInstance(
            schema = schema,
            value = input,
            errorCode = ErrorCode.AGENT_INPUT_SCHEMA_INVALID,
        )
    }

    private data class StepPresentation(
        val responseFormat: AgentResponseFormat,
        val agentCode: String?,
        val agentName: String?,
    )
}
