package com.agentstore.execution.runner

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.orchestrator.ExecutionPaymentOrchestrator
import com.agentstore.execution.codec.RuntimeOutputEnvelope
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.service.ExecutionLifecycleService
import com.agentstore.execution.service.ExecutionRunService
import com.agentstore.execution.service.ProviderMetricService
import com.agentstore.execution.model.vo.AgentInvocationOutcome
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
    private val capabilityService: AgentCapabilityService,
    private val providerMetricService: ProviderMetricService,
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
        val snapshot = quoteService.snapshot(quote)
        val version = snapshot.path("version")
        val endpoint = version.path("endpoint").asText()
        val cost = version.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
        try {
            val agentInput = buildMap<String, Any?> {
                put(key = "input", value = initialExecution.input)
                initialExecution.question?.let { question ->
                    put(key = "question", value = question)
                }
            }
            val runtimeDependencies =
                snapshot.path("dependencies").filter { it.path("resolved").isObject }
                    .map { dependency ->
                        val resolved = dependency.path("resolved")
                        mapOf(
                            "agentVersionId" to resolved.path("version").path("id").asText(),
                            "callPath" to (step.callPath.map { it.asText() } + resolved.path("version")
                                .path("agentCode")
                                .asText()),
                            "input" to agentInput,
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
                put(key = "input", value = agentInput)
                put(key = "runtime", value = runtime)
            }
            val rawOutput = paymentOrchestrator.invoke(
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
            val format = responseFormat(version = version)
            val output = RuntimeOutputEnvelope.extract(rawOutput)
            AgentOutputFormatValidator.validate(
                format = format,
                output = output,
            )
            validateOutputSchema(version = version, output = output)
            executionLifecycleService.complete(
                executionId = executionId,
                stepId = step.id,
                output = output,
                costAtomic = cost,
            )
            providerMetricService.finish(
                stepId = step.id,
                outcome = AgentInvocationOutcome.SUCCESS,
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
                is DomainClientException -> exception.errorCode.name
                else -> "AGENT_INVOCATION_FAILED"
            }
            executionLifecycleService.fail(
                executionId = executionId,
                stepId = step.id,
                failureCode = failureCode,
            )
            providerMetricService.finish(
                stepId = step.id,
                outcome = metricOutcome(exception = exception),
            )
        }
    }

    private fun metricOutcome(exception: Exception): AgentInvocationOutcome {
        return when (exception) {
            is AgentOutputFormatException -> AgentInvocationOutcome.OUTPUT_FORMAT_INVALID
            is DomainClientException -> {
                if (exception.errorCode == ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID) {
                    AgentInvocationOutcome.OUTPUT_SCHEMA_INVALID
                } else {
                    AgentInvocationOutcome.PLATFORM_FAILURE
                }
            }
            is PaymentExecutionException -> AgentInvocationOutcome.PLATFORM_FAILURE
            else -> AgentInvocationOutcome.PLATFORM_FAILURE
        }
    }

    private fun responseFormat(version: JsonNode): AgentResponseFormat {
        return runCatching { AgentResponseFormat.valueOf(version.path("responseFormat").asText()) }
            .getOrDefault(AgentResponseFormat.JSON)
    }

    private fun validateOutputSchema(version: JsonNode, output: JsonNode) {
        val schema = version.path("functionContract").path("outputSchema")
        if (!schema.isObject) {
            return
        }
        capabilityService.validateInstance(
            schema = schema,
            value = output,
            errorCode = ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
        )
    }
}
