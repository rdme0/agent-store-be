package com.agentstore.execution.runner

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.orchestrator.ExecutionPaymentOrchestrator
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.service.ExecutionLifecycleService
import com.agentstore.execution.service.ExecutionRunService
import com.agentstore.payment.exception.PaymentExecutionException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.util.*

@Component
class ExecutionRunner(
    private val executionRepository: ExecutionRepository,
    private val executionStepRepository: ExecutionStepRepository,
    private val quoteService: QuoteService,
    private val paymentOrchestrator: ExecutionPaymentOrchestrator,
    private val executionRunService: ExecutionRunService,
    private val executionLifecycleService: ExecutionLifecycleService,
    private val properties: AgentStoreProperties,
) {
    private val logger = LoggerFactory.getLogger(ExecutionRunner::class.java)

    @Async
    fun start(executionId: UUID) {
        val initialExecution = executionRepository.findById(executionId).orElse(null) ?: return
        val step = executionStepRepository.findAllByExecutionIdOrderByCreatedAtAsc(executionId).firstOrNull()
        if (step == null) {
            executionRunService.claim(executionId)
            executionRunService.fail(executionId, "EXECUTION_STEP_NOT_FOUND")
            return
        }
        if (!executionRunService.claim(executionId, step.id)) {
            return
        }
        val quote = quoteService.findQuoteOrNull(initialExecution.quoteId)
        if (quote == null) {
            executionLifecycleService.fail(executionId, step.id, "EXECUTION_QUOTE_NOT_FOUND")
            return
        }
        val snapshot = quote.snapshot
        val version = snapshot.path("version")
        val endpoint = version.path("endpoint").asText()
        val cost = version.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
        try {
            val runtimeDependencies =
                snapshot.path("dependencies").filter { it.path("resolved").isObject }.map { dependency ->
                    val resolved = dependency.path("resolved")
                    mapOf(
                        "agentVersionId" to resolved.path("version").path("id").asText(),
                        "callPath" to (step.callPath.map { it.asText() } + resolved.path("version").path("agentSlug")
                            .asText()),
                        "input" to emptyMap<String, Any>(),
                    )
                }
            val runtime = mapOf(
                "executionId" to executionId,
                "parentStepId" to step.id,
                "callbackUrl" to "${properties.runtimeCallbackBaseUrl.trimEnd('/')}/api/runtime/executions/$executionId/dependencies/invoke",
                "dependencies" to runtimeDependencies,
            )
            val body = buildMap<String, Any?> {
                put("input", initialExecution.input)
                put("runtime", runtime)
                initialExecution.question?.let { put("question", it) }
            }
            val output = paymentOrchestrator.invoke(
                executionId = executionId,
                stepId = step.id,
                endpoint = endpoint,
                amount = cost,
                network = version.path("network").asText(),
                asset = version.path("asset").asText(),
                payTo = version.path("payTo").asText(),
                body = body,
            ).output
            executionLifecycleService.complete(executionId, step.id, output, cost)
        } catch (exception: Exception) {
            logger.error("execution runner failed executionId={} stepId={}", executionId, step.id, exception)
            val failureCode = (exception as? PaymentExecutionException)?.failureCode ?: "AGENT_INVOCATION_FAILED"
            executionLifecycleService.fail(executionId, step.id, failureCode)
        }
    }
}
