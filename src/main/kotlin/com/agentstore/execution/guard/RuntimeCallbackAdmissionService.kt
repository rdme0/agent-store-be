package com.agentstore.execution.guard

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.execution.exception.ExecutionNotFoundException
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import java.util.UUID
import org.springframework.stereotype.Component

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
            ?: throw ExecutionNotFoundException()
        if (execution.status != ExecutionStatus.RUNNING) {
            throw DomainClientException(ErrorCode.EXECUTION_NOT_ACTIVE)
        }
        val parent = stepRepository.findByIdForUpdate(parentStepId)
            ?: throw DomainClientException(ErrorCode.RUNTIME_STEP_NOT_FOUND)
        if (parent.executionId != executionId || parent.status !in setOf(
                ExecutionStepStatus.PAYMENT_REQUIRED,
                ExecutionStepStatus.PAYMENT_SETTLED,
                ExecutionStepStatus.RUNNING
            )
        ) {
            throw DomainClientException(ErrorCode.PARENT_STEP_NOT_ACTIVE)
        }
        stepRepository.findByParentStepIdAndIdempotencyKey(
            parentStepId = parentStepId,
            idempotencyKey = idempotencyKey,
        )
            ?.let { return it }
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
