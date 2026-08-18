package com.agentstore.execution.orchestrator

import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.service.RevenueSettlementService
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.util.UUID

@Component
class ExecutionPaymentOrchestrator(
    private val budgetGuard: BudgetGuard,
    private val stepService: ExecutionStepService,
    private val paymentService: PaymentService,
    private val preparationService: ExecutionPaymentPreparationService,
    private val paymentClient: PaymentClient,
    private val eventService: ExecutionEventService,
    private val revenueSettlementService: RevenueSettlementService,
) {
    fun invoke(
        executionId: UUID,
        stepId: UUID,
        endpoint: String,
        amount: BigInteger,
        network: String,
        asset: String,
        payTo: String,
        body: Any?,
    ): PaymentInvocationResult {
        val attemptId = preparationService.prepare(executionId, stepId, amount, network, asset, payTo)
        var settlementRecorded = false
        return try {
            val result = paymentClient.invoke(PaymentInvocationRequest(attemptId.toString(), attemptId.toString(), endpoint, amount.toString(), network, asset, payTo, body))
            val transactionHash = result.transactionHash ?: "simulated:$attemptId"
            paymentService.settle(attemptId, transactionHash, result.paymentIdentifier)
            settlementRecorded = true
            val settledAttempt = paymentService.find(attemptId)
            revenueSettlementService.record(settledAttempt, RevenueType.DIRECT)
            budgetGuard.settle(executionId, amount)
            stepService.markPaymentSettled(stepId)
            eventService.append(executionId, "PAYMENT_SETTLED", mapOf("stepId" to stepId, "paymentAttemptId" to attemptId, "amountAtomic" to amount.toString(), "transactionHash" to transactionHash, "paymentMode" to PaymentMode.SIMULATED.name))
            stepService.markRunning(stepId)
            result
        } catch (exception: Exception) {
            if (settlementRecorded) {
                paymentService.markReconciliationRequired(attemptId, "PAYMENT_RECONCILIATION_REQUIRED")
                stepService.fail(stepId, "PAYMENT_RECONCILIATION_REQUIRED")
            } else {
                paymentService.fail(attemptId, "PAYMENT_FAILED")
                budgetGuard.release(executionId, amount)
                stepService.fail(stepId, "PAYMENT_FAILED")
            }
            throw PaymentExecutionException(if (settlementRecorded) "PAYMENT_RECONCILIATION_REQUIRED" else "PAYMENT_FAILED", exception)
        }
    }

}
