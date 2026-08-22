package com.agentstore.execution.orchestrator

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.service.RevenueSettlementService
import jakarta.transaction.Transactional
import java.util.UUID
import org.springframework.stereotype.Component

/** Atomically projects a journal-backed external settlement; it never invokes a payer. */
@Component
class ExecutionPaymentRecoveryProjectionOrchestrator(
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val paymentService: PaymentService,
    private val budgetGuard: BudgetGuard,
    private val revenueSettlementService: RevenueSettlementService,
    private val eventService: ExecutionEventService,
) {
    @Transactional
    fun project(attemptId: UUID): Boolean {
        // Shared lock order for recovery, callback and terminalization: execution → step → attempt.
        val attempt = paymentService.find(attemptId)
        val step = stepRepository.findById(attempt.executionStepId)
            .orElseThrow { IllegalStateException("execution_step_not_found") }
        val execution =
            executionRepository.findByIdForUpdate(step.executionId) ?: error("execution_not_found")
        val lockedStep =
            stepRepository.findByIdForUpdate(step.id) ?: error("execution_step_not_found")
        val lockedAttempt = paymentService.findForUpdate(attemptId)
        if (lockedAttempt.status != PaymentAttemptStatus.SETTLED) {
            return false
        }

        if (lockedAttempt.projectedAt != null) {
            return false
        }
        budgetGuard.reconcile(
            executionId = execution.id,
            amount = lockedAttempt.amountAtomic,
        )
        if (lockedStep.status == ExecutionStepStatus.PAYMENT_REQUIRED) {
            lockedStep.paymentSettled()
            stepRepository.save(lockedStep)
        }
        val type = if (lockedStep.parentStepId == null) {
            RevenueType.DIRECT
        } else {
            RevenueType.DEPENDENCY
        }
        revenueSettlementService.record(attempt = lockedAttempt, type = type)
        paymentService.markProjected(lockedAttempt.id)
        eventService.append(
            executionId = execution.id,
            type = "PAYMENT_SETTLED",
            payload = mapOf(
                "stepId" to lockedStep.id,
                "paymentAttemptId" to lockedAttempt.id,
                "amountAtomic" to lockedAttempt.amountAtomic.toString(),
                "transactionHash" to lockedAttempt.transactionHash,
                "recovered" to true,
            ),
        )
        return true
    }
}
