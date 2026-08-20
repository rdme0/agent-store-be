package com.agentstore.execution.orchestrator

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.model.vo.ExecutionStepStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.payment.service.PaymentExternalSettlementService
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.service.RevenueSettlementService
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.util.*

/** Applies a known external settlement only while the locked execution state remains active. */
@Component
class ExecutionPaymentSettlementService(
    private val executionRepository: ExecutionRepository,
    private val stepRepository: ExecutionStepRepository,
    private val paymentExternalSettlementService: PaymentExternalSettlementService,
    private val paymentService: PaymentService,
    private val budgetGuard: BudgetGuard,
    private val revenueSettlementService: RevenueSettlementService,
    private val eventService: ExecutionEventService,
) {
    @Transactional
    fun settleIfActive(
        executionId: UUID,
        stepId: UUID,
        attemptId: UUID,
        amount: BigInteger,
        transactionHash: String,
        paymentIdentifier: String?,
        revenueType: RevenueType,
        paymentMode: PaymentMode,
    ): Boolean {
        // Global execution state-machine lock order: execution → step → payment attempt.
        val execution = executionRepository.findByIdForUpdate(executionId) ?: error("execution_not_found")
        val step = stepRepository.findByIdForUpdate(stepId) ?: error("execution_step_not_found")
        if (step.executionId != executionId) {
            error("execution_step_mismatch")
        }

        paymentExternalSettlementService.record(attemptId, transactionHash, paymentIdentifier)
        if (execution.status != ExecutionStatus.RUNNING || step.status != ExecutionStepStatus.PAYMENT_REQUIRED) {
            paymentService.markSettlementRecoveryRequired(attemptId, "FAILED_AFTER_PAYMENT")
            return false
        }

        val attempt = paymentService.find(attemptId)
        budgetGuard.settle(executionId, amount)
        revenueSettlementService.record(attempt, revenueType)
        step.paymentSettled()
        step.running()
        stepRepository.save(step)
        eventService.append(
            executionId,
            "PAYMENT_SETTLED",
            mapOf(
                "stepId" to stepId,
                "paymentAttemptId" to attemptId,
                "amountAtomic" to amount.toString(),
                "transactionHash" to transactionHash,
                "paymentMode" to paymentMode.name,
            ),
        )
        // Normal and recovery projections share one durable per-attempt idempotency proof.
        // This outer transaction rolls back marker, budget, revenue, step and event together.
        paymentService.markProjected(attemptId)
        return true
    }
}
