package com.agentstore.execution.service

import com.agentstore.common.web.ApiException
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.dto.response.RuntimeDependencyInvocationResponse
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.orchestrator.ExecutionPaymentOrchestrator
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.revenue.model.vo.RevenueType
import com.fasterxml.jackson.databind.JsonNode
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.UUID

@Service
class RuntimeCallbackService(
    private val tokenService: InvocationTokenService,
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val quoteService: QuoteService,
    private val stepService: ExecutionStepService,
    private val paymentOrchestrator: ExecutionPaymentOrchestrator,
    private val eventService: ExecutionEventService,
) {
    fun invoke(executionId: UUID, request: RuntimeDependencyInvocationRequest, authorization: String?, idempotencyKey: String?): RuntimeDependencyInvocationResponse {
        val token = authorization?.removePrefix("Bearer ")?.takeIf { it != authorization && it.isNotBlank() }
            ?: throw ApiException("INVALID_INVOCATION_TOKEN", "Bearer invocation token is required", 401)
        val claims = tokenService.verify(token)
        if (claims.executionId != executionId) throw ApiException("INVALID_INVOCATION_TOKEN", "Invocation token claims do not match execution", 401)
        val key = idempotencyKey?.takeIf { it.isNotBlank() } ?: throw ApiException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required", 400)
        val parent = stepRepository.findById(claims.stepId).orElseThrow { ApiException("RUNTIME_STEP_NOT_FOUND", "Parent execution step was not found", 404) }
        val execution = executionRepository.findById(executionId).orElseThrow { ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404) }
        if (parent.executionId != executionId || claims.agentVersionId != parent.agentVersionId || claims.callPath != parent.callPath.map { it.asText() }) throw ApiException("INVALID_INVOCATION_TOKEN", "Invocation token claims do not match parent step", 401)
        if (execution.status != ExecutionStatus.RUNNING) throw ApiException("EXECUTION_NOT_ACTIVE", "Execution is no longer accepting runtime callbacks", 409)
        if (parent.status != ExecutionStepStatus.PAYMENT_REQUIRED && parent.status != ExecutionStepStatus.PAYMENT_SETTLED && parent.status != ExecutionStepStatus.RUNNING) throw ApiException("PARENT_STEP_NOT_ACTIVE", "Parent step is no longer accepting callbacks", 409)
        val existing = stepRepository.findByParentStepIdAndIdempotencyKey(parent.id, key)
        if (existing != null) {
            if (existing.status == ExecutionStepStatus.COMPLETED) return RuntimeDependencyInvocationResponse(existing.id, existing.output, existing.costAtomic.toString())
            throw ApiException("IDEMPOTENCY_IN_PROGRESS", "The dependency invocation is already in progress", 409, mapOf("stepId" to existing.id))
        }
        val snapshot = quoteService.snapshot(execution.quoteId)
        val targetVersionId = request.agentVersionId ?: throw ApiException("VALIDATION_ERROR", "agentVersionId is required", 422)
        val requestedPath = request.callPath ?: throw ApiException("VALIDATION_ERROR", "callPath is required", 422)
        val dependency = findDirectDependency(snapshot, parent.agentVersionId, parent.callPath.map { it.asText() }, targetVersionId.toString())
            ?: throw ApiException("UNDECLARED_DEPENDENCY", "Agent version is not a declared direct dependency", 403)
        val target = dependency.path("resolved").path("version")
        val expectedPath = parent.callPath.map { it.asText() } + target.path("agentSlug").asText()
        if (requestedPath != expectedPath || requestedPath.size > 5) throw ApiException("INVALID_CALL_PATH", "Invocation callPath is not connected to the persisted parent step", 403, mapOf("expectedPath" to expectedPath))
        val child = stepService.findOrCreateDependency(executionId, parent.id, targetVersionId, requestedPath, key)
        return try {
            val cost = target.path("priceAtomic").asText("0").toBigIntegerOrNull() ?: BigInteger.ZERO
            val output = paymentOrchestrator.invoke(executionId, child.id, target.path("endpoint").asText(), cost, target.path("network").asText(), target.path("asset").asText(), target.path("payTo").asText(), mapOf("input" to request.input), RevenueType.DEPENDENCY).output
            stepService.complete(child.id, output, cost)
            eventService.append(executionId, "DEPENDENCY_STEP_COMPLETED", mapOf("stepId" to child.id, "parentStepId" to parent.id, "costAtomic" to cost.toString()))
            RuntimeDependencyInvocationResponse(child.id, output, cost.toString())
        } catch (exception: PaymentExecutionException) {
            stepService.fail(child.id, exception.failureCode)
            throw exception
        } catch (exception: Exception) {
            stepService.fail(child.id, "DEPENDENCY_INVOCATION_FAILED")
            throw ApiException("DEPENDENCY_INVOCATION_FAILED", "Dependency invocation failed", 502)
        }
    }

    private fun findDirectDependency(root: JsonNode, parentVersionId: UUID, parentPath: List<String>, targetVersionId: String): JsonNode? {
        fun visit(node: JsonNode, path: List<String>): JsonNode? {
            if (node.path("version").path("id").asText() == parentVersionId.toString() && path == parentPath) {
                return node.path("dependencies").firstOrNull { it.path("resolved").path("version").path("id").asText() == targetVersionId }
            }
            return node.path("dependencies").firstNotNullOfOrNull { dependency ->
                val resolved = dependency.path("resolved")
                if (resolved.isMissingNode || resolved.isNull) null else visit(resolved, path + resolved.path("version").path("agentSlug").asText())
            }
        }
        return visit(root, listOf(root.path("version").path("agentSlug").asText()))
    }
}
