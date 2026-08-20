package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.BridgeReconciliationResult
import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.BridgeReconciliationStatus

/** Simulated payments never need an external lookup; x402 recovery owns reconciliation. */
class NoopPaymentReconciliationClient : PaymentReconciliationClient {
    override fun reconcile(attempt: PaymentAttempt): BridgeReconciliationResult {
        return BridgeReconciliationResult(BridgeReconciliationStatus.UNKNOWN)
    }
}
