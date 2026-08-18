package com.agentstore.execution.service

import com.agentstore.common.web.ApiException
import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.execution.dto.request.CreateExecutionRequest
import com.agentstore.execution.dto.response.ExecutionResponse
import com.agentstore.execution.dto.response.ExecutionStepResponse
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.model.entity.Execution
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.runner.ExecutionRunner
import com.agentstore.payment.repository.PaymentAttemptRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

@Service
class ExecutionService(
    private val executionRepository: ExecutionRepository,
    private val executionStepRepository: ExecutionStepRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val quoteRepository: ExecutionQuoteRepository,
    private val eventService: ExecutionEventService,
    private val objectMapper: ObjectMapper,
    private val runner: ExecutionRunner,
) {
    @Transactional
    fun create(request: CreateExecutionRequest): ExecutionResponse {
        val quote = quoteRepository.findById(request.quoteId).orElseThrow { ApiException("QUOTE_NOT_FOUND", "Execution quote was not found", 404, mapOf("quoteId" to request.quoteId)) }
        if (!quote.expiresAt.isAfter(Instant.now())) throw ApiException("QUOTE_EXPIRED", "Execution quote has expired", 409, mapOf("quoteId" to request.quoteId))
        val budget = request.maxBudgetAtomic.toBigIntegerOrNull() ?: throw ApiException("INVALID_PRICE", "maxBudgetAtomic must be an atomic integer", 400)
        if (budget != quote.maxCostAtomic) throw ApiException("BUDGET_MISMATCH", "maxBudgetAtomic must equal the quote maximum cost", 422, mapOf("expected" to quote.maxCostAtomic.toString()))
        val execution = executionRepository.save(Execution(UUID.randomUUID(), quote.id, budget, request.question, request.input))
        val rootVersionId = quote.snapshot.path("version").path("id").asText().takeIf { it.isNotBlank() }?.let(UUID::fromString) ?: quote.rootVersionId
        val rootStep = executionStepRepository.save(ExecutionStep(UUID.randomUUID(), execution.id, null, rootVersionId, objectMapper.valueToTree(listOf(quote.snapshot.path("version").path("agentSlug").asText()))))
        eventService.append(execution.id, "EXECUTION_CREATED", mapOf("quoteId" to quote.id, "maxBudgetAtomic" to budget.toString()))
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
        val execution = executionRepository.findById(id).orElseThrow { ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404, mapOf("id" to id)) }
        return toResponse(execution, executionStepRepository.findAllByExecutionIdOrderByCreatedAtAsc(id))
    }

    @Transactional
    fun events(id: UUID, afterSequence: Int) : List<com.agentstore.execution.dto.response.ExecutionEventResponse> {
        if (!executionRepository.existsById(id)) throw ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404, mapOf("id" to id))
        return eventService.replay(id, afterSequence)
    }

    fun subscribe(id: UUID, afterSequence: Int): SseEmitter {
        if (!executionRepository.existsById(id)) throw ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404, mapOf("id" to id))
        return eventService.subscribe(id, afterSequence)
    }

    private fun toResponse(execution: Execution, steps: List<ExecutionStep>) = ExecutionResponse(execution.id, execution.quoteId, execution.status.name, execution.maxBudgetAtomic.toString(), execution.reservedCostAtomic.toString(), execution.actualCostAtomic.toString(), execution.question, execution.input, execution.failureCode, steps.map { step -> ExecutionStepResponse.from(step, paymentAttemptRepository.findAllByExecutionStepIdOrderByCreatedAtAsc(step.id)) }, execution.createdAt, execution.updatedAt)
}
