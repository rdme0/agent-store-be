package com.agentstore.execution.runner

import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import java.math.BigInteger
import java.util.UUID

@Service
class ExecutionRunner(
    private val executionRepository: ExecutionRepository,
    private val executionStepRepository: ExecutionStepRepository,
    private val quoteRepository: ExecutionQuoteRepository,
    private val eventService: ExecutionEventService,
    private val paymentClient: PaymentClient,
) {
    @Async
    fun start(executionId: UUID) {
        val execution = executionRepository.findById(executionId).orElse(null) ?: return
        if (execution.status != ExecutionStatus.PENDING) return
        val step = executionStepRepository.findAllByExecutionIdOrderByCreatedAtAsc(executionId).firstOrNull() ?: return
        val quote = quoteRepository.findById(execution.quoteId).orElse(null) ?: return
        val version = quote.snapshot.path("version")
        val endpoint = version.path("endpoint").asText()
        val cost = version.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
        execution.start()
        executionRepository.save(execution)
        eventService.append(executionId, "EXECUTION_RUNNING", mapOf("stepId" to step.id))
        try {
            val output = paymentClient.invoke(PaymentInvocationRequest(endpoint, cost.toString(), mapOf("input" to execution.input, "question" to execution.question))).output
            step.complete(output, cost)
            execution.complete(cost)
            executionStepRepository.save(step)
            executionRepository.save(execution)
            eventService.append(executionId, "EXECUTION_COMPLETED", mapOf("stepId" to step.id, "actualCostAtomic" to cost.toString(), "output" to output))
        } catch (exception: Exception) {
            step.fail("AGENT_INVOCATION_FAILED")
            execution.fail("AGENT_INVOCATION_FAILED")
            executionStepRepository.save(step)
            executionRepository.save(execution)
            eventService.append(executionId, "EXECUTION_FAILED", mapOf("stepId" to step.id, "failureCode" to "AGENT_INVOCATION_FAILED"))
        }
    }
}
