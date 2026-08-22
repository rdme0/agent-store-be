package com.agentstore.execution.runner

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.orchestrator.ExecutionPaymentOrchestrator
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.service.ExecutionLifecycleService
import com.agentstore.execution.service.ExecutionRunService
import com.agentstore.execution.validation.AgentOutputFormatException
import com.agentstore.execution.validation.AgentOutputFormatValidator
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.revenue.model.vo.RevenueType
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

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
        val step = executionStepRepository.findAllByExecutionIdOrderByCreatedAtAsc(executionId)
            .firstOrNull()
        if (step == null) {
            executionRunService.claim(executionId)
            executionRunService.fail(
                executionId = executionId,
                failureCode = "EXECUTION_STEP_NOT_FOUND",
            )
            return
        }
        if (!executionRunService.claim(executionId = executionId, stepId = step.id)) {
            return
        }
        val quote = quoteService.findQuoteOrNull(initialExecution.quoteId)
        if (quote == null) {
            executionLifecycleService.fail(
                executionId = executionId,
                stepId = step.id,
                failureCode = "EXECUTION_QUOTE_NOT_FOUND",
            )
            return
        }
        val snapshot = quote.snapshot
        val version = snapshot.path("version")
        val endpoint = version.path("endpoint").asText()
        val cost = version.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
        try {
            val runtimeDependencies =
                snapshot.path("dependencies").filter { it.path("resolved").isObject }
                    .map { dependency ->
                        val resolved = dependency.path("resolved")
                        mapOf(
                            "agentVersionId" to resolved.path("version").path("id").asText(),
                            "callPath" to (step.callPath.map { it.asText() } + resolved.path("version")
                                .path("agentSlug")
                                .asText()),
                            "input" to emptyMap<String, Any>(),
                        )
                    }
            val runtime = mapOf(
                "executionId" to executionId,
                "parentStepId" to step.id,
                "callbackUrl" to properties.runtimeCallbackBaseUrl.trimEnd('/') +
                    "/api/runtime/executions/$executionId/dependencies/invoke",
                "dependencies" to runtimeDependencies,
            )
            val body = buildMap<String, Any?> {
                put(key = "input", value = initialExecution.input)
                put(key = "runtime", value = runtime)
                initialExecution.question?.let { question ->
                    put(key = "question", value = question)
                }
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
                revenueType = RevenueType.DIRECT,
                maxPriceAtomic = cost,
            ).output
            AgentOutputFormatValidator.validate(
                format = responseFormat(version = version),
                output = output,
            )
            executionLifecycleService.complete(
                executionId = executionId,
                stepId = step.id,
                output = output,
                costAtomic = cost,
            )
        } catch (exception: Exception) {
            logger.error(
                "execution runner failed executionId={} stepId={}",
                executionId,
                step.id,
                exception
            )
            val failureCode = when (exception) {
                is PaymentExecutionException -> exception.failureCode
                is AgentOutputFormatException -> "AGENT_OUTPUT_FORMAT_INVALID"
                else -> "AGENT_INVOCATION_FAILED"
            }
            executionLifecycleService.fail(
                executionId = executionId,
                stepId = step.id,
                failureCode = failureCode,
            )
        }
    }

    private fun responseFormat(version: JsonNode): AgentResponseFormat {
        return runCatching { AgentResponseFormat.valueOf(version.path("responseFormat").asText()) }
            .getOrDefault(AgentResponseFormat.JSON)
    }
}
