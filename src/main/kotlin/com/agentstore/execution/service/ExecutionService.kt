package com.agentstore.execution.service

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.request.CreateExecutionRequest
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
) {
    @Transactional
    fun create(request: CreateExecutionRequest): ExecutionResponse {
        mutationReadiness.requireReady()
        val quote = quoteService.requireQuote(request.quoteId)
        if (!quote.expiresAt.isAfter(Instant.now())) {
            throw DomainClientException(ErrorCode.QUOTE_EXPIRED)
        }
        val budget = request.maxBudgetAtomic.toBigIntegerOrNull() ?: throw DomainClientException(
            ErrorCode.INVALID_BUDGET
        )
        if (budget != quote.maxCostAtomic) {
            throw DomainClientException(ErrorCode.BUDGET_MISMATCH)
        }
        val input = request.input?.let {
            objectMapper.valueToTree<JsonNode>(it)
        }
        val execution =
            executionRepository.save(
                Execution(
                    UUID.randomUUID(),
                    quote.id,
                    budget,
                    request.question,
                    input
                )
            )
        val rootVersionId =
            quote.snapshot.path("version").path("id").asText().takeIf { it.isNotBlank() }
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
                        quote.snapshot.path("version").path("agentSlug").asText()
                    )
                )
            )
        )
        eventService.append(
            executionId = execution.id,
            type = "EXECUTION_CREATED",
            payload = mapOf("quoteId" to quote.id, "maxBudgetAtomic" to budget.toString()),
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
        val responseFormats = responseFormats(quoteService.snapshot(execution.quoteId))
        return ExecutionResponse(
            id = execution.id,
            quoteId = execution.quoteId,
            status = execution.status.name,
            maxBudgetAtomic = execution.maxBudgetAtomic.toString(),
            reservedCostAtomic = execution.reservedCostAtomic.toString(),
            actualCostAtomic = execution.actualCostAtomic.toString(),
            question = execution.question,
            input = jsonValue(value = execution.input),
            failureCode = execution.failureCode,
            steps = steps.map { step ->
                ExecutionStepResponse.from(
                    step = step,
                    payments = paymentService.findAllByStepId(step.id),
                    output = jsonValue(value = step.output),
                    responseFormat = responseFormats[step.agentVersionId] ?: AgentResponseFormat.JSON,
                )
            },
            createdAt = execution.createdAt,
            updatedAt = execution.updatedAt,
        )
    }

    private fun responseFormats(snapshot: JsonNode): Map<UUID, AgentResponseFormat> {
        val result = mutableMapOf<UUID, AgentResponseFormat>()

        fun visit(node: JsonNode) {
            val version = node.path("version")
            val id = version.path("id").asText().takeIf { it.isNotBlank() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id != null) {
                result[id] = runCatching {
                    AgentResponseFormat.valueOf(
                        version.path("responseFormat").asText()
                    )
                }
                    .getOrDefault(AgentResponseFormat.JSON)
            }
            node.path("dependencies").forEach { dependency ->
                val resolved = dependency.path("resolved")
                if (resolved.isObject) visit(resolved)
            }
        }

        visit(snapshot)
        return result
    }
}
