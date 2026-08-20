package com.agentstore.execution.service

import com.agentstore.common.exception.ApiException
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.request.CreateExecutionRequest
import com.agentstore.execution.dto.response.ExecutionResponse
import com.agentstore.execution.dto.response.ExecutionStepResponse
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.ExecutionMutationReadiness
import com.agentstore.execution.model.entity.Execution
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.runner.ExecutionRunner
import com.agentstore.payment.service.PaymentService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.*

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
            throw ApiException("QUOTE_EXPIRED", "Execution quote has expired", 409, mapOf("quoteId" to request.quoteId))
        }
        val budget = request.maxBudgetAtomic.toBigIntegerOrNull() ?: throw ApiException(
            "INVALID_PRICE",
            "maxBudgetAtomic must be an atomic integer",
            400
        )
        if (budget != quote.maxCostAtomic) {
            throw ApiException(
                "BUDGET_MISMATCH",
                "maxBudgetAtomic must equal the quote maximum cost",
                422,
                mapOf("expected" to quote.maxCostAtomic.toString())
            )
        }
        val input = request.input?.let { objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(it) }
        val execution =
            executionRepository.save(Execution(UUID.randomUUID(), quote.id, budget, request.question, input))
        val rootVersionId =
            quote.snapshot.path("version").path("id").asText().takeIf { it.isNotBlank() }?.let(UUID::fromString)
                ?: quote.rootVersionId
        val rootStep = executionStepRepository.save(
            ExecutionStep(
                UUID.randomUUID(),
                execution.id,
                null,
                rootVersionId,
                objectMapper.valueToTree(listOf(quote.snapshot.path("version").path("agentSlug").asText()))
            )
        )
        eventService.append(
            execution.id,
            "EXECUTION_CREATED",
            mapOf("quoteId" to quote.id, "maxBudgetAtomic" to budget.toString())
        )
        val executionId = execution.id
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                runner.start(executionId)
            }
        })
        return toResponse(execution, listOf(rootStep))
    }

    @Transactional
    fun get(id: UUID): ExecutionResponse {
        val execution = executionRepository.findById(id)
            .orElseThrow { ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404, mapOf("id" to id)) }
        return toResponse(execution, executionStepRepository.findAllByExecutionIdOrderByCreatedAtAsc(id))
    }

    @Transactional
    fun events(id: UUID, afterSequence: Int): List<com.agentstore.execution.dto.response.ExecutionEventResponse> {
        if (!executionRepository.existsById(id)) {
            throw ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404, mapOf("id" to id))
        }
        return eventService.replay(id, afterSequence)
    }

    fun subscribe(id: UUID, lastEventId: String?): SseEmitter {
        if (!executionRepository.existsById(id)) {
            throw ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404, mapOf("id" to id))
        }
        return eventService.subscribe(id, lastEventId?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
    }

    private fun jsonValue(value: com.fasterxml.jackson.databind.JsonNode?): Any? {
        return value?.let { objectMapper.convertValue(it, Any::class.java) }
    }

    private fun toResponse(execution: Execution, steps: List<ExecutionStep>): ExecutionResponse {
        return ExecutionResponse(
            execution.id,
            execution.quoteId,
            execution.status.name,
            execution.maxBudgetAtomic.toString(),
            execution.reservedCostAtomic.toString(),
            execution.actualCostAtomic.toString(),
            execution.question,
            jsonValue(execution.input),
            execution.failureCode,
            steps.map { step ->
                ExecutionStepResponse.from(
                    step,
                    paymentService.findAllByStepId(step.id),
                    jsonValue(step.output)
                )
            },
            execution.createdAt,
            execution.updatedAt,
        )
    }
}
