package com.agentstore.execution.orchestrator

import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.service.RevenueSettlementService
import org.springframework.stereotype.Component

@Component
class ExecutionPaymentRecoveryOrchestrator(
    private val paymentService: PaymentService,
    private val stepService: ExecutionStepService,
    private val budgetGuard: BudgetGuard,
    private val revenueSettlementService: RevenueSettlementService,
) {
    fun reconcileSettledPayments(): Int {
        var recovered = 0
        paymentService.findSettledAttempts().forEach { attempt ->
            val executionId = stepService.executionId(attempt.executionStepId) ?: return@forEach
            if (!stepService.isPaymentSettled(attempt.executionStepId)) {
                budgetGuard.reconcile(executionId, attempt.amountAtomic)
                stepService.markPaymentSettled(attempt.executionStepId)
            }
            revenueSettlementService.record(attempt, RevenueType.DIRECT)
            recovered++
        }
        return recovered
    }
}
