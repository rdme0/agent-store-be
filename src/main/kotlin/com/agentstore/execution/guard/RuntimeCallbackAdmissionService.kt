package com.agentstore.execution.guard

import com.agentstore.common.exception.ApiException
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component
import java.util.*

/**
 * Admission is a locking guard, not a general execution use case. It owns the
 * execution -> parent-step lock order used by callback and terminal recovery.
 */
@Component
class RuntimeCallbackAdmissionService(
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun admit(
        executionId: UUID,
        parentStepId: UUID,
        agentVersionId: UUID,
        callPath: List<String>,
        idempotencyKey: String
    ): ExecutionStep {
        val execution = executionRepository.findByIdForUpdate(executionId)
            ?: throw ApiException("EXECUTION_NOT_FOUND", "Execution was not found", 404)
        if (execution.status != ExecutionStatus.RUNNING) {
            throw ApiException("EXECUTION_NOT_ACTIVE", "Execution is no longer accepting runtime callbacks", 409)
        }
        val parent = stepRepository.findByIdForUpdate(parentStepId)
            ?: throw ApiException("RUNTIME_STEP_NOT_FOUND", "Parent execution step was not found", 404)
        if (parent.executionId != executionId || parent.status !in setOf(
                ExecutionStepStatus.PAYMENT_REQUIRED,
                ExecutionStepStatus.PAYMENT_SETTLED,
                ExecutionStepStatus.RUNNING
            )
        ) {
            throw ApiException("PARENT_STEP_NOT_ACTIVE", "Parent step is no longer accepting callbacks", 409)
        }
        stepRepository.findByParentStepIdAndIdempotencyKey(parentStepId, idempotencyKey)?.let { return it }
        return stepRepository.save(
            ExecutionStep(
                UUID.randomUUID(),
                executionId,
                parentStepId,
                agentVersionId,
                objectMapper.valueToTree(callPath),
                idempotencyKey
            )
        )
    }
}
