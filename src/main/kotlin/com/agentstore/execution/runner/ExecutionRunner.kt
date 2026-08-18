package com.agentstore.execution.runner

import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.orchestrator.ExecutionPaymentOrchestrator
import com.agentstore.execution.service.ExecutionRunService
import com.agentstore.execution.service.ExecutionLifecycleService
import com.agentstore.payment.exception.PaymentExecutionException
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.UUID

@Service
class ExecutionRunner(
    private val executionRepository: ExecutionRepository,
    private val executionStepRepository: ExecutionStepRepository,
    private val quoteRepository: ExecutionQuoteRepository,
    private val paymentOrchestrator: ExecutionPaymentOrchestrator,
    private val executionRunService: ExecutionRunService,
    private val executionLifecycleService: ExecutionLifecycleService,
) {
    @Async
    fun start(executionId: UUID) {
        val initialExecution = executionRepository.findById(executionId).orElse(null) ?: return
        val step = executionStepRepository.findAllByExecutionIdOrderByCreatedAtAsc(executionId).firstOrNull()
        if (step == null) {
            executionRunService.claim(executionId)
            executionRunService.fail(executionId, "EXECUTION_STEP_NOT_FOUND")
            return
        }
        if (!executionRunService.claim(executionId, step.id)) return
        val quote = quoteRepository.findById(initialExecution.quoteId).orElse(null)
        if (quote == null) {
            executionLifecycleService.fail(executionId, step.id, "EXECUTION_QUOTE_NOT_FOUND")
            return
        }
        val version = quote.snapshot.path("version")
        val endpoint = version.path("endpoint").asText()
        val cost = version.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
        try {
            val output = paymentOrchestrator.invoke(
                executionId = executionId,
                stepId = step.id,
                endpoint = endpoint,
                amount = cost,
                network = version.path("network").asText(),
                asset = version.path("asset").asText(),
                payTo = version.path("payTo").asText(),
                body = mapOf("input" to initialExecution.input, "question" to initialExecution.question),
            ).output
            executionLifecycleService.complete(executionId, step.id, output, cost)
        } catch (exception: Exception) {
            val failureCode = (exception as? PaymentExecutionException)?.failureCode ?: "AGENT_INVOCATION_FAILED"
            executionLifecycleService.fail(executionId, step.id, failureCode)
        }
    }
}
