package com.agentstore.execution.orchestrator

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.payment.service.PaymentService
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.UUID

@Service
class ExecutionPaymentPreparationService(
    private val budgetGuard: BudgetGuard,
    private val stepService: ExecutionStepService,
    private val paymentService: PaymentService,
    private val eventService: ExecutionEventService,
) {
    @Transactional
    fun prepare(executionId: UUID, stepId: UUID, amount: BigInteger, network: String, asset: String, payTo: String): UUID {
        budgetGuard.reserve(executionId, amount)
        val attemptId = paymentService.require(stepId, amount, network, asset, payTo, PaymentMode.SIMULATED)
        stepService.markPaymentRequired(stepId)
        eventService.append(executionId, "PAYMENT_REQUIRED", mapOf("stepId" to stepId, "paymentAttemptId" to attemptId, "amountAtomic" to amount.toString()))
        return attemptId
    }
}
