package com.agentstore.execution.service

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.dto.response.RuntimeDependencyInvocationResponse
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.exception.ExecutionNotFoundException
import com.agentstore.execution.guard.ExecutionMutationReadiness
import com.agentstore.execution.guard.RuntimeCallbackAdmissionService
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.model.vo.AgentInvocationOutcome
import com.agentstore.execution.orchestrator.ExecutionPaymentOrchestrator
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.execution.validation.AgentOutputFormatException
import com.agentstore.execution.validation.AgentOutputFormatValidator
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.revenue.model.vo.RevenueType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class RuntimeCallbackService(
    private val tokenService: InvocationTokenService,
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val quoteService: QuoteService,
    private val stepService: ExecutionStepService,
    private val executionLifecycleService: ExecutionLifecycleService,
    private val admissionService: RuntimeCallbackAdmissionService,
    private val paymentOrchestrator: ExecutionPaymentOrchestrator,
    private val eventService: ExecutionEventService,
    private val mutationReadiness: ExecutionMutationReadiness,
    private val capabilityService: AgentCapabilityService,
    private val objectMapper: ObjectMapper,
    private val providerMetricService: ProviderMetricService,
) {
    fun invoke(
        executionId: UUID,
        request: RuntimeDependencyInvocationRequest,
        authorization: String?,
        idempotencyKey: String?
    ): RuntimeDependencyInvocationResponse {
        mutationReadiness.requireReady()
        val token = authorization?.removePrefix("Bearer ")
            ?.takeIf { it != authorization && it.isNotBlank() }
            ?: throw DomainClientException(ErrorCode.INVALID_INVOCATION_TOKEN)
        val claims = tokenService.verify(token)
        if (claims.executionId != executionId) {
            throw DomainClientException(ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        val key = idempotencyKey?.takeIf { it.isNotBlank() } ?: throw DomainClientException(
            ErrorCode.IDEMPOTENCY_KEY_REQUIRED
        )
        val execution = executionRepository.findById(executionId)
            .orElseThrow { ExecutionNotFoundException() }
        val parent = stepRepository.findById(claims.stepId)
            .orElseThrow { DomainClientException(ErrorCode.RUNTIME_STEP_NOT_FOUND) }
        val tokenDoesNotMatchParent = parent.executionId != executionId ||
            claims.agentVersionId != parent.agentVersionId ||
            claims.callPath != parent.callPath.map { node -> node.asText() }
        if (tokenDoesNotMatchParent) {
            throw DomainClientException(ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        if (execution.status != ExecutionStatus.RUNNING) {
            throw DomainClientException(ErrorCode.EXECUTION_NOT_ACTIVE)
        }
        val parentIsNotInvocable = parent.status != ExecutionStepStatus.PAYMENT_REQUIRED &&
            parent.status != ExecutionStepStatus.PAYMENT_SETTLED &&
            parent.status != ExecutionStepStatus.RUNNING
        if (parentIsNotInvocable) {
            throw DomainClientException(ErrorCode.PARENT_STEP_NOT_ACTIVE)
        }
        val existing = stepRepository.findByParentStepIdAndIdempotencyKey(
            parentStepId = parent.id,
            idempotencyKey = key,
        )
        if (existing != null) {
            if (existing.status == ExecutionStepStatus.COMPLETED) {
                return RuntimeDependencyInvocationResponse(
                    stepId = existing.id,
                    output = jsonValue(value = existing.output),
                    costAtomic = existing.costAtomic.toString(),
                )
            }
            throw DomainClientException(ErrorCode.IDEMPOTENCY_IN_PROGRESS)
        }
        val snapshot = quoteService.snapshot(execution.quoteId)
        val targetVersionId =
            request.agentVersionId ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        val requestedPath =
            request.callPath ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        val dependency = findDirectDependency(
            root = snapshot,
            parentVersionId = parent.agentVersionId,
            parentPath = parent.callPath.map { node -> node.asText() },
            targetVersionId = targetVersionId.toString(),
        )
            ?: throw DomainClientException(ErrorCode.UNDECLARED_DEPENDENCY)
        val target = dependency.path("resolved").path("version")
        val expectedPath = parent.callPath.map { it.asText() } + target.path("agentSlug").asText()
        if (requestedPath != expectedPath || requestedPath.size > 5) {
            throw DomainClientException(ErrorCode.INVALID_CALL_PATH)
        }
        try {
            validateInputSchema(
                version = target,
                input = request.input,
            )
        } catch (exception: DomainClientException) {
            if (exception.errorCode == ErrorCode.AGENT_INPUT_SCHEMA_INVALID) {
                executionLifecycleService.fail(
                    executionId = executionId,
                    stepId = parent.id,
                    failureCode = ErrorCode.AGENT_INPUT_SCHEMA_INVALID.name,
                )
            }
            throw exception
        }
        val child =
            admissionService.admit(
                executionId = executionId,
                parentStepId = parent.id,
                agentVersionId = targetVersionId,
                callPath = requestedPath,
                idempotencyKey = key,
            )
        return try {
            val cost =
                target.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
            val maxPrice = dependency.path("maxPriceAtomic").asText().toBigIntegerOrNull()
                ?: throw DomainClientException(ErrorCode.INVALID_DEPENDENCY_LIMIT)
            val rawOutput = paymentOrchestrator.invoke(
                executionId = executionId,
                stepId = child.id,
                endpoint = target.path("endpoint").asText(),
                amount = cost,
                network = target.path("network").asText(),
                asset = target.path("asset").asText(),
                payTo = target.path("payTo").asText(),
                body = mapOf("input" to request.input),
                revenueType = RevenueType.DEPENDENCY,
                maxPriceAtomic = maxPrice,
            ).output
            val format = responseFormat(version = target)
            val output = outputForFormat(
                rawOutput = rawOutput,
                format = format,
            )
            AgentOutputFormatValidator.validate(
                format = format,
                output = output,
            )
            validateOutputSchema(
                version = target,
                output = output,
            )
            stepService.complete(stepId = child.id, output = output, costAtomic = cost)
            eventService.append(
                executionId = executionId,
                type = "DEPENDENCY_STEP_COMPLETED",
                payload = mapOf(
                    "stepId" to child.id,
                    "parentStepId" to parent.id,
                    "costAtomic" to cost.toString(),
                ),
            )
            providerMetricService.finish(
                stepId = child.id,
                outcome = AgentInvocationOutcome.SUCCESS,
            )
            RuntimeDependencyInvocationResponse(
                stepId = child.id,
                output = jsonValue(value = output),
                costAtomic = cost.toString(),
            )
        } catch (exception: PaymentExecutionException) {
            stepService.fail(stepId = child.id, failureCode = exception.failureCode)
            providerMetricService.finish(
                stepId = child.id,
                outcome = AgentInvocationOutcome.PLATFORM_FAILURE,
            )
            throw exception
        } catch (exception: AgentOutputFormatException) {
            executionLifecycleService.fail(
                executionId = executionId,
                stepId = child.id,
                failureCode = "AGENT_OUTPUT_FORMAT_INVALID",
            )
            providerMetricService.finish(
                stepId = child.id,
                outcome = AgentInvocationOutcome.OUTPUT_FORMAT_INVALID,
            )
            throw DomainClientException(ErrorCode.DEPENDENCY_INVOCATION_FAILED)
        } catch (exception: DomainClientException) {
            if (exception.errorCode != ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID) {
                stepService.fail(
                    stepId = child.id,
                    failureCode = "DEPENDENCY_INVOCATION_FAILED",
                )
                throw DomainClientException(ErrorCode.DEPENDENCY_INVOCATION_FAILED)
            }
            executionLifecycleService.fail(
                executionId = executionId,
                stepId = child.id,
                failureCode = ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID.name,
            )
            providerMetricService.finish(
                stepId = child.id,
                outcome = AgentInvocationOutcome.OUTPUT_SCHEMA_INVALID,
            )
            throw DomainClientException(ErrorCode.DEPENDENCY_INVOCATION_FAILED)
        } catch (exception: Exception) {
            stepService.fail(stepId = child.id, failureCode = "DEPENDENCY_INVOCATION_FAILED")
            providerMetricService.finish(
                stepId = child.id,
                outcome = AgentInvocationOutcome.PLATFORM_FAILURE,
            )
            throw DomainClientException(ErrorCode.DEPENDENCY_INVOCATION_FAILED)
        }
    }

    private fun responseFormat(version: JsonNode): AgentResponseFormat {
        return runCatching { AgentResponseFormat.valueOf(version.path("responseFormat").asText()) }
            .getOrDefault(AgentResponseFormat.JSON)
    }

    private fun outputForFormat(rawOutput: JsonNode, format: AgentResponseFormat): JsonNode {
        if (format == AgentResponseFormat.JSON) {
            return rawOutput
        }
        return rawOutput.get("output") ?: rawOutput
    }

    private fun validateInputSchema(version: JsonNode, input: Any?) {
        val schema = version.path("functionContract").path("inputSchema")
        if (!schema.isObject) {
            return
        }
        capabilityService.validateInstance(
            schema = schema,
            value = objectMapper.valueToTree(input),
            errorCode = ErrorCode.AGENT_INPUT_SCHEMA_INVALID,
        )
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

    private fun jsonValue(value: JsonNode?): Any? {
        return value?.let { objectMapper.convertValue(it, Any::class.java) }
    }

    private fun findDirectDependency(
        root: JsonNode,
        parentVersionId: UUID,
        parentPath: List<String>,
        targetVersionId: String
    ): JsonNode? {
        fun visit(node: JsonNode, path: List<String>): JsonNode? {
            if (node.path("version").path("id")
                    .asText() == parentVersionId.toString() && path == parentPath
            ) {
                return node.path("dependencies")
                    .firstOrNull {
                        it.path("resolved").path("version").path("id").asText() == targetVersionId
                    }
            }
            return node.path("dependencies").firstNotNullOfOrNull { dependency ->
                val resolved = dependency.path("resolved")
                if (resolved.isMissingNode || resolved.isNull) {
                    null
                } else {
                    visit(
                        node = resolved,
                        path = path + resolved.path("version").path("agentSlug").asText(),
                    )
                }
            }
        }
        return visit(
            node = root,
            path = listOf(root.path("version").path("agentSlug").asText()),
        )
    }
}
