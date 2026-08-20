package com.agentstore.execution.service

import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.repository.ExecutionStepRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.*

@Service
class ExecutionStepService(
    private val repository: ExecutionStepRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun markPaymentRequired(stepId: UUID) {
        val step = repository.findByIdForUpdate(stepId) ?: error("execution_step_not_found")
        if (step.status != ExecutionStepStatus.CREATED && step.status != ExecutionStepStatus.PAYMENT_REQUIRED) {
            return
        }
        step.paymentRequired()
        repository.save(step)
    }

    @Transactional
    fun markPaymentSettled(stepId: UUID) {
        val step = repository.findByIdForUpdate(stepId) ?: error("execution_step_not_found")
        if (step.status != ExecutionStepStatus.PAYMENT_REQUIRED) {
            return
        }
        step.paymentSettled()
        repository.save(step)
    }

    @Transactional
    fun markRunning(stepId: UUID) {
        val step = repository.findByIdForUpdate(stepId) ?: error("execution_step_not_found")
        if (step.status != ExecutionStepStatus.PAYMENT_SETTLED && step.status != ExecutionStepStatus.RUNNING) {
            return
        }
        step.running()
        repository.save(step)
    }

    @Transactional
    fun fail(stepId: UUID, failureCode: String) {
        val step = repository.findByIdForUpdate(stepId) ?: return
        if (step.status == ExecutionStepStatus.FAILED || step.status == ExecutionStepStatus.COMPLETED) {
            return
        }
        step.fail(failureCode)
        repository.save(step)
    }

    @Transactional
    fun complete(stepId: UUID, output: JsonNode, costAtomic: java.math.BigInteger) {
        val step = repository.findByIdForUpdate(stepId) ?: error("execution_step_not_found")
        if (step.status != ExecutionStepStatus.RUNNING && step.status != ExecutionStepStatus.COMPLETED) {
            return
        }
        step.complete(output, costAtomic)
        repository.save(step)
    }

    fun executionId(stepId: UUID): UUID? {
        return repository.findById(stepId).map { it.executionId }.orElse(null)
    }

    fun agentVersionId(stepId: UUID): UUID? {
        return repository.findById(stepId).map { it.agentVersionId }.orElse(null)
    }

    fun callPath(stepId: UUID): List<String> {
        return repository.findById(stepId).map { step -> step.callPath.map { it.asText() } }.orElse(emptyList())
    }

    fun isPaymentSettled(stepId: UUID): Boolean {
        return repository.findById(stepId).map { step ->
            step.status == ExecutionStepStatus.PAYMENT_SETTLED || step.status == ExecutionStepStatus.RUNNING || step.status == ExecutionStepStatus.COMPLETED
        }.orElse(false)
    }

    @Transactional
    fun findOrCreateDependency(
        executionId: UUID,
        parentStepId: UUID,
        agentVersionId: UUID,
        callPath: List<String>,
        idempotencyKey: String
    ): com.agentstore.execution.model.entity.ExecutionStep {
        repository.findByParentStepIdAndIdempotencyKey(parentStepId, idempotencyKey)?.let { return it }
        return repository.save(
            com.agentstore.execution.model.entity.ExecutionStep(
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
