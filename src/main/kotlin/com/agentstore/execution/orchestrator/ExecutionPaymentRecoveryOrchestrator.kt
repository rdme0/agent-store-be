package com.agentstore.execution.orchestrator

import com.agentstore.payment.service.PaymentSettlementRecoveryService
import org.springframework.stereotype.Component

@Component
class ExecutionPaymentRecoveryOrchestrator(
    private val paymentSettlementRecoveryService: PaymentSettlementRecoveryService,
) {
    fun reconcileSettledPayments(): Int {
        return paymentSettlementRecoveryService.recoverAll()
    }
}
