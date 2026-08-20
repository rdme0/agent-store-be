package com.agentstore.execution.service

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import com.agentstore.payment.service.PaymentService
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class ExecutionRecoveryService(
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val paymentService: PaymentService,
    private val eventService: ExecutionEventService,
) {
    @Transactional
    fun failActiveExecutions(): Int {
        var recovered = 0
        executionRepository.findAllByStatusIn(listOf(ExecutionStatus.PENDING, ExecutionStatus.RUNNING))
            .forEach { candidate ->
                val execution = executionRepository.findByIdForUpdate(candidate.id) ?: return@forEach
                if (execution.status != ExecutionStatus.PENDING && execution.status != ExecutionStatus.RUNNING) {
                    return@forEach
                }
                val steps = stepRepository.findAllByExecutionIdOrderByCreatedAtAsc(execution.id)
                val unresolved = steps.any { step ->
                    paymentService.findAllByStepId(step.id).any {
                        it.status == PaymentAttemptStatus.RECONCILIATION_REQUIRED ||
                                (it.status == PaymentAttemptStatus.SETTLED && it.projectedAt == null)
                    }
                }
                steps.filter { it.status == ExecutionStepStatus.CREATED || it.status == ExecutionStepStatus.PAYMENT_REQUIRED || it.status == ExecutionStepStatus.PAYMENT_SETTLED || it.status == ExecutionStepStatus.RUNNING }
                    .forEach { step ->
                        val failureCode = if (unresolved) {
                            "PAYMENT_RECONCILIATION_REQUIRED"
                        } else {
                            "SERVER_RESTART"
                        }
                        step.fail(failureCode)
                        stepRepository.save(step)
                    }
                if (!unresolved) {
                    execution.clearReservation()
                }
                val failureCode = if (unresolved) {
                    "PAYMENT_RECONCILIATION_REQUIRED"
                } else {
                    "SERVER_RESTART"
                }
                execution.fail(failureCode)
                executionRepository.save(execution)
                eventService.append(
                    execution.id,
                    "EXECUTION_FAILED",
                    mapOf("failureCode" to execution.failureCode, "recovered" to true)
                )
                recovered++
            }
        return recovered
    }
}
