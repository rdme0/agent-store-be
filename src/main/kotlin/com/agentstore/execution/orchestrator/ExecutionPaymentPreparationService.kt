package com.agentstore.execution.orchestrator

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.payment.service.PaymentService
import jakarta.transaction.Transactional
import java.math.BigInteger
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ExecutionPaymentPreparationService(
    private val budgetGuard: BudgetGuard,
    private val stepService: ExecutionStepService,
    private val paymentService: PaymentService,
    private val eventService: ExecutionEventService,
) {
    @Transactional
    fun prepare(
        executionId: UUID,
        stepId: UUID,
        amount: BigInteger,
        network: String,
        asset: String,
        payTo: String,
        paymentMode: PaymentMode
    ): UUID {
        budgetGuard.reserve(executionId = executionId, amount = amount)
        val attemptId = paymentService.require(
            stepId = stepId,
            amount = amount,
            network = network,
            asset = asset,
            payTo = payTo,
            mode = paymentMode,
        )
        stepService.markPaymentRequired(stepId = stepId)
        eventService.append(
            executionId = executionId,
            type = "PAYMENT_REQUIRED",
            payload = mapOf(
                "stepId" to stepId,
                "paymentAttemptId" to attemptId,
                "amountAtomic" to amount.toString(),
            ),
        )
        return attemptId
    }
}
