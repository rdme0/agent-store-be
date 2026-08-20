package com.agentstore.execution.service

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.execution.exception.ExecutionNotFoundException
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.dto.response.RuntimeDependencyInvocationResponse
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.ExecutionMutationReadiness
import com.agentstore.execution.guard.RuntimeCallbackAdmissionService
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.orchestrator.ExecutionPaymentOrchestrator
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.revenue.model.vo.RevenueType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.*

@Service
class RuntimeCallbackService(
    private val tokenService: InvocationTokenService,
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val quoteService: QuoteService,
    private val stepService: ExecutionStepService,
    private val admissionService: RuntimeCallbackAdmissionService,
    private val paymentOrchestrator: ExecutionPaymentOrchestrator,
    private val eventService: ExecutionEventService,
    private val mutationReadiness: ExecutionMutationReadiness,
    private val objectMapper: ObjectMapper,
) {
    fun invoke(
        executionId: UUID,
        request: RuntimeDependencyInvocationRequest,
        authorization: String?,
        idempotencyKey: String?
    ): RuntimeDependencyInvocationResponse {
        mutationReadiness.requireReady()
        val token = authorization?.removePrefix("Bearer ")?.takeIf { it != authorization && it.isNotBlank() }
            ?: throw DomainClientException(ErrorCode.INVALID_INVOCATION_TOKEN)
        val claims = tokenService.verify(token)
        if (claims.executionId != executionId) {
            throw DomainClientException(ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        val key = idempotencyKey?.takeIf { it.isNotBlank() } ?: throw DomainClientException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED)
        val execution = executionRepository.findById(executionId)
            .orElseThrow { ExecutionNotFoundException() }
        val parent = stepRepository.findById(claims.stepId)
            .orElseThrow { DomainClientException(ErrorCode.RUNTIME_STEP_NOT_FOUND) }
        if (parent.executionId != executionId || claims.agentVersionId != parent.agentVersionId || claims.callPath != parent.callPath.map { it.asText() }) {
            throw DomainClientException(ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        if (execution.status != ExecutionStatus.RUNNING) {
            throw DomainClientException(ErrorCode.EXECUTION_NOT_ACTIVE)
        }
        if (parent.status != ExecutionStepStatus.PAYMENT_REQUIRED && parent.status != ExecutionStepStatus.PAYMENT_SETTLED && parent.status != ExecutionStepStatus.RUNNING) {
            throw DomainClientException(ErrorCode.PARENT_STEP_NOT_ACTIVE)
        }
        val existing = stepRepository.findByParentStepIdAndIdempotencyKey(parent.id, key)
        if (existing != null) {
            if (existing.status == ExecutionStepStatus.COMPLETED) {
                return RuntimeDependencyInvocationResponse(
                    existing.id,
                    jsonValue(existing.output),
                    existing.costAtomic.toString()
                )
            }
            throw DomainClientException(ErrorCode.IDEMPOTENCY_IN_PROGRESS)
        }
        val snapshot = quoteService.snapshot(execution.quoteId)
        val targetVersionId =
            request.agentVersionId ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        val requestedPath = request.callPath ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        val dependency = findDirectDependency(
            snapshot,
            parent.agentVersionId,
            parent.callPath.map { it.asText() },
            targetVersionId.toString()
        )
            ?: throw DomainClientException(ErrorCode.UNDECLARED_DEPENDENCY)
        val target = dependency.path("resolved").path("version")
        val expectedPath = parent.callPath.map { it.asText() } + target.path("agentSlug").asText()
        if (requestedPath != expectedPath || requestedPath.size > 5) {
            throw DomainClientException(ErrorCode.INVALID_CALL_PATH)
        }
        val child = admissionService.admit(executionId, parent.id, targetVersionId, requestedPath, key)
        return try {
            val cost = target.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
            val maxPrice = dependency.path("maxPriceAtomic").asText().toBigIntegerOrNull()
                ?: throw DomainClientException(ErrorCode.INVALID_DEPENDENCY_LIMIT)
            val output = paymentOrchestrator.invoke(
                executionId,
                child.id,
                target.path("endpoint").asText(),
                cost,
                target.path("network").asText(),
                target.path("asset").asText(),
                target.path("payTo").asText(),
                mapOf("input" to request.input),
                RevenueType.DEPENDENCY,
                maxPrice
            ).output
            stepService.complete(child.id, output, cost)
            eventService.append(
                executionId,
                "DEPENDENCY_STEP_COMPLETED",
                mapOf("stepId" to child.id, "parentStepId" to parent.id, "costAtomic" to cost.toString())
            )
            RuntimeDependencyInvocationResponse(child.id, jsonValue(output), cost.toString())
        } catch (exception: PaymentExecutionException) {
            stepService.fail(child.id, exception.failureCode)
            throw exception
        } catch (exception: Exception) {
            stepService.fail(child.id, "DEPENDENCY_INVOCATION_FAILED")
            throw DomainClientException(ErrorCode.DEPENDENCY_INVOCATION_FAILED)
        }
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
            if (node.path("version").path("id").asText() == parentVersionId.toString() && path == parentPath) {
                return node.path("dependencies")
                    .firstOrNull { it.path("resolved").path("version").path("id").asText() == targetVersionId }
            }
            return node.path("dependencies").firstNotNullOfOrNull { dependency ->
                val resolved = dependency.path("resolved")
                if (resolved.isMissingNode || resolved.isNull) {
                    null
                } else {
                    visit(resolved, path + resolved.path("version").path("agentSlug").asText())
                }
            }
        }
        return visit(root, listOf(root.path("version").path("agentSlug").asText()))
    }
}
