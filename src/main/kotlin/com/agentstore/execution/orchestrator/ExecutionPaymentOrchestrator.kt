package com.agentstore.execution.orchestrator

import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.util.*

@Component
class ExecutionPaymentOrchestrator(
    private val budgetGuard: BudgetGuard,
    private val stepService: ExecutionStepService,
    private val paymentService: PaymentService,
    private val preparationService: ExecutionPaymentPreparationService,
    private val paymentClient: PaymentClient,
    private val settlementService: ExecutionPaymentSettlementService,
    private val invocationTokenService: InvocationTokenService,
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
        revenueType: RevenueType = RevenueType.DIRECT,
        maxPriceAtomic: BigInteger = amount,
    ): PaymentInvocationResult {
        require(amount <= maxPriceAtomic) { "payment amount exceeds maxPriceAtomic" }
        val attemptId =
            preparationService.prepare(executionId, stepId, amount, network, asset, payTo, paymentClient.mode)
        var externalPaymentObserved = false
        return try {
            val agentVersionId = stepService.agentVersionId(stepId) ?: error("agent_version_not_found")
            val token = invocationTokenService.issue(executionId, stepId, agentVersionId, stepService.callPath(stepId))
            val result = paymentClient.invoke(
                PaymentInvocationRequest(
                    attemptId.toString(),
                    attemptId.toString(),
                    token,
                    endpoint,
                    amount.toString(),
                    maxPriceAtomic.toString(),
                    network,
                    asset,
                    payTo,
                    body
                )
            )
            val transactionHash = result.transactionHash ?: "simulated:$attemptId"
            externalPaymentObserved = true
            if (!settlementService.settleIfActive(
                    executionId,
                    stepId,
                    attemptId,
                    amount,
                    transactionHash,
                    result.paymentIdentifier,
                    revenueType,
                    paymentClient.mode
                )
            ) {
                throw PaymentExecutionException(
                    "PAYMENT_RECONCILIATION_REQUIRED",
                    IllegalStateException("execution_terminalized_after_payment")
                )
            }
            if (result.agentStatus !in 200..299) {
                stepService.fail(stepId, "FAILED_AFTER_PAYMENT")
                throw PaymentExecutionException(
                    "FAILED_AFTER_PAYMENT",
                    IllegalStateException("paid_agent_status_${result.agentStatus}")
                )
            }
            result
        } catch (exception: Exception) {
            if (exception is PaymentOutcomeUnknownException) {
                paymentService.markReconciliationRequired(attemptId, exception.failureCode)
                stepService.fail(stepId, exception.failureCode)
            } else if (externalPaymentObserved) {
                if (exception !is PaymentExecutionException || exception.failureCode != "FAILED_AFTER_PAYMENT") {
                    paymentService.markSettlementRecoveryRequired(attemptId, "FAILED_AFTER_PAYMENT")
                    stepService.fail(stepId, "FAILED_AFTER_PAYMENT")
                }
            } else {
                paymentService.fail(attemptId, "PAYMENT_FAILED")
                budgetGuard.release(executionId, amount)
                stepService.fail(stepId, "PAYMENT_FAILED")
            }
            if (exception is PaymentExecutionException) {
                throw exception
            }
            if (exception is PaymentOutcomeUnknownException) {
                throw PaymentExecutionException(exception.failureCode, exception)
            }
            val failureCode = if (externalPaymentObserved) {
                "PAYMENT_RECONCILIATION_REQUIRED"
            } else {
                "PAYMENT_FAILED"
            }
            throw PaymentExecutionException(failureCode, exception)
        }
    }

}
