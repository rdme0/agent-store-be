package com.agentstore.execution.orchestrator

import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.execution.service.ProviderMetricService
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.execution.model.vo.AgentInvocationOutcome
import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.payment.dto.internal.PaymentInvocationResultDto
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import java.math.BigInteger
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ExecutionPaymentOrchestrator(
    private val budgetGuard: BudgetGuard,
    private val stepService: ExecutionStepService,
    private val paymentService: PaymentService,
    private val preparationService: ExecutionPaymentPreparationService,
    private val paymentClient: PaymentClient,
    private val settlementService: ExecutionPaymentSettlementService,
    private val invocationTokenService: InvocationTokenService,
    private val providerMetricService: ProviderMetricService,
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
        revenueType: RevenueType,
        maxPriceAtomic: BigInteger,
    ): PaymentInvocationResultDto {
        require(amount <= maxPriceAtomic) { "payment amount exceeds maxPriceAtomic" }
        val attemptId = preparationService.prepare(
            executionId = executionId,
            stepId = stepId,
            amount = amount,
            network = network,
            asset = asset,
            payTo = payTo,
            paymentMode = paymentClient.mode,
        )
        var externalPaymentObserved = false
        return try {
            val agentVersionId =
                stepService.agentVersionId(stepId) ?: error("agent_version_not_found")
            val token = invocationTokenService.issue(
                executionId = executionId,
                stepId = stepId,
                agentVersionId = agentVersionId,
                callPath = stepService.callPath(stepId = stepId),
            )
            providerMetricService.start(stepId = stepId, agentVersionId = agentVersionId)
            val result = paymentClient.invoke(
                PaymentInvocationRequestDto(
                    paymentAttemptId = attemptId.toString(),
                    idempotencyKey = attemptId.toString(),
                    invocationToken = token,
                    endpoint = endpoint,
                    amountAtomic = amount.toString(),
                    maxPriceAtomic = maxPriceAtomic.toString(),
                    network = network,
                    asset = asset,
                    payTo = payTo,
                    body = body,
                )
            )
            val transactionHash = result.transactionHash ?: "simulated:$attemptId"
            externalPaymentObserved = true
            if (!settlementService.settleIfActive(
                    executionId = executionId,
                    stepId = stepId,
                    attemptId = attemptId,
                    amount = amount,
                    transactionHash = transactionHash,
                    paymentIdentifier = result.paymentIdentifier,
                    revenueType = revenueType,
                    paymentMode = paymentClient.mode,
                )
            ) {
                throw PaymentExecutionException(
                    failureCode = "PAYMENT_RECONCILIATION_REQUIRED",
                    cause = IllegalStateException("execution_terminalized_after_payment"),
                )
            }
            if (result.agentStatus !in 200..299) {
                providerMetricService.finish(
                    stepId = stepId,
                    outcome = AgentInvocationOutcome.AGENT_HTTP_FAILURE,
                )
                stepService.fail(stepId = stepId, failureCode = "FAILED_AFTER_PAYMENT")
                throw PaymentExecutionException(
                    failureCode = "FAILED_AFTER_PAYMENT",
                    cause = IllegalStateException("paid_agent_status_${result.agentStatus}"),
                )
            }
            result
        } catch (exception: Exception) {
            if (exception is PaymentOutcomeUnknownException) {
                providerMetricService.finish(
                    stepId = stepId,
                    outcome = AgentInvocationOutcome.PAYMENT_RECONCILIATION_REQUIRED,
                )
                paymentService.markReconciliationRequired(
                    attemptId = attemptId,
                    failureCode = exception.failureCode,
                )
                stepService.fail(stepId = stepId, failureCode = exception.failureCode)
            } else if (externalPaymentObserved) {
                providerMetricService.finish(
                    stepId = stepId,
                    outcome = AgentInvocationOutcome.PLATFORM_FAILURE,
                )
                if (exception !is PaymentExecutionException || exception.failureCode != "FAILED_AFTER_PAYMENT") {
                    paymentService.markSettlementRecoveryRequired(
                        attemptId = attemptId,
                        failureCode = "FAILED_AFTER_PAYMENT",
                    )
                    stepService.fail(stepId = stepId, failureCode = "FAILED_AFTER_PAYMENT")
                }
            } else {
                providerMetricService.finish(
                    stepId = stepId,
                    outcome = AgentInvocationOutcome.PAYMENT_FAILURE,
                )
                paymentService.fail(attemptId = attemptId, failureCode = "PAYMENT_FAILED")
                budgetGuard.release(executionId = executionId, amount = amount)
                stepService.fail(stepId = stepId, failureCode = "PAYMENT_FAILED")
            }
            if (exception is PaymentExecutionException) {
                throw exception
            }
            if (exception is PaymentOutcomeUnknownException) {
                throw PaymentExecutionException(
                    failureCode = exception.failureCode,
                    cause = exception,
                )
            }
            val failureCode = if (externalPaymentObserved) {
                "PAYMENT_RECONCILIATION_REQUIRED"
            } else {
                "PAYMENT_FAILED"
            }
            throw PaymentExecutionException(failureCode = failureCode, cause = exception)
        }
    }

}
