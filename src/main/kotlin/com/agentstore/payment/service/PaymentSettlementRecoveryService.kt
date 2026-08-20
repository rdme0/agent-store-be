package com.agentstore.payment.service

import com.agentstore.execution.orchestrator.ExecutionPaymentRecoveryProjectionOrchestrator
import com.agentstore.execution.service.ExecutionLifecycleService
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.client.PaymentReconciliationClient
import com.agentstore.payment.model.vo.BridgeReconciliationStatus
import com.agentstore.payment.model.vo.PaymentMode
import org.springframework.stereotype.Service

/** Replays journal-backed local settlement work without invoking an external payer again. */
@Service
class PaymentSettlementRecoveryService(
    private val paymentService: PaymentService,
    private val stepService: ExecutionStepService,
    private val executionLifecycleService: ExecutionLifecycleService,
    private val paymentExternalSettlementService: PaymentExternalSettlementService,
    private val reconciliationClient: PaymentReconciliationClient,
    private val projectionOrchestrator: ExecutionPaymentRecoveryProjectionOrchestrator,
) {
    fun recoverAll(): Int {
        var recovered = 0
        paymentService.findSettledAttempts().forEach { attempt ->
            recovered += projectSettledAttempt(attempt.id)
        }
        paymentService.findReconciliationRequiredAttempts().forEach { attempt ->
            if (attempt.paymentMode != PaymentMode.X402) {
                return@forEach
            }
            val result = reconciliationClient.reconcile(attempt)
            // Neither a bridge restart/transport UNKNOWN nor a 4xx request rejection can prove
            // that the original signed payment did not settle. Both retain the reservation.
            if (result.status != BridgeReconciliationStatus.SETTLED) {
                return@forEach
            }
            paymentExternalSettlementService.record(attempt.id, result.transactionHash!!, result.paymentIdentifier)
            recovered += projectSettledAttempt(attempt.id)
        }
        return recovered
    }

    private fun projectSettledAttempt(attemptId: java.util.UUID): Int {
        val attempt = paymentService.find(attemptId)
        val executionId = stepService.executionId(attempt.executionStepId) ?: return 0
        return try {
            if (projectionOrchestrator.project(attempt.id)) {
                1
            } else {
                0
            }
        } catch (_: Exception) {
            // A settled journal is external-payment evidence. Never release its reservation
            // or downgrade the attempt when local reconciliation cannot finish.
            paymentService.markSettlementRecoveryRequired(attempt.id, "FAILED_AFTER_PAYMENT")
            executionLifecycleService.fail(executionId, attempt.executionStepId, "FAILED_AFTER_PAYMENT")
            0
        }
    }
}
