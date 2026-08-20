package com.agentstore.execution.service

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.*

@Service
class ExecutionRunService(
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val eventService: ExecutionEventService,
) {
    @Transactional
    fun claim(executionId: UUID): Boolean {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.PENDING) {
            return false
        }
        execution.start()
        executionRepository.save(execution)
        return true
    }

    @Transactional
    fun claim(executionId: UUID, stepId: UUID): Boolean {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.PENDING) {
            return false
        }
        execution.start()
        executionRepository.save(execution)
        eventService.append(executionId, "EXECUTION_RUNNING", mapOf("stepId" to stepId))
        return true
    }

    @Transactional
    fun complete(executionId: UUID) {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: return
        if (execution.status != ExecutionStatus.RUNNING) {
            return
        }
        execution.complete()
        executionRepository.save(execution)
    }

    @Transactional
    fun fail(executionId: UUID, failureCode: String) {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: return
        if (execution.status == ExecutionStatus.COMPLETED || execution.status == ExecutionStatus.FAILED) {
            return
        }
        execution.fail(failureCode)
        executionRepository.save(execution)
        stepRepository.findAllByExecutionIdOrderByCreatedAtAsc(executionId)
            .filter { it.status != com.agentstore.execution.model.vo.ExecutionStepStatus.COMPLETED && it.status != com.agentstore.execution.model.vo.ExecutionStepStatus.FAILED }
            .forEach { it.fail(failureCode); stepRepository.save(it) }
    }
}
