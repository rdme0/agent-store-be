package com.agentstore.execution.service

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.fasterxml.jackson.databind.JsonNode
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.*

@Service
class ExecutionLifecycleService(
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val eventService: ExecutionEventService,
) {
    @Transactional
    fun complete(executionId: UUID, stepId: UUID, output: JsonNode, costAtomic: BigInteger) {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: return
        val step = stepRepository.findByIdForUpdate(stepId) ?: return
        if (step.status == ExecutionStepStatus.RUNNING) {
            step.complete(output, costAtomic)
        }
        if (execution.status == ExecutionStatus.RUNNING) {
            execution.complete()
        }
        stepRepository.save(step)
        executionRepository.save(execution)
        eventService.append(
            executionId,
            "EXECUTION_COMPLETED",
            mapOf("stepId" to stepId, "actualCostAtomic" to execution.actualCostAtomic.toString(), "output" to output)
        )
    }

    @Transactional
    fun fail(executionId: UUID, stepId: UUID, failureCode: String) {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: return
        val step = stepRepository.findByIdForUpdate(stepId) ?: return
        if (step.status != ExecutionStepStatus.COMPLETED && step.status != ExecutionStepStatus.FAILED) {
            step.fail(failureCode)
        }
        if (execution.status != ExecutionStatus.COMPLETED && execution.status != ExecutionStatus.FAILED) {
            execution.fail(failureCode)
        }
        stepRepository.save(step)
        executionRepository.save(execution)
        eventService.append(
            executionId,
            "EXECUTION_FAILED",
            mapOf("stepId" to stepId, "failureCode" to execution.failureCode)
        )
    }
}
