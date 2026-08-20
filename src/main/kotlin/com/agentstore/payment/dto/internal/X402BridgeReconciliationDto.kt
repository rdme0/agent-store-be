package com.agentstore.payment.dto.internal

import com.agentstore.payment.model.vo.BridgeReconciliationStatus

data class X402BridgeReconciliationRequest(
    val paymentAttemptId: String,
    val idempotencyKey: String,
    val transactionHash: String? = null,
    val paymentIdentifier: String? = null,
)

data class X402BridgeReconciliationResponse(
    val status: String,
    val transactionHash: String? = null,
    val paymentIdentifier: String? = null,
)

data class BridgeReconciliationResult(
    val status: BridgeReconciliationStatus,
    val transactionHash: String? = null,
    val paymentIdentifier: String? = null,
) {
    val settled: Boolean
        get() {
            return status == BridgeReconciliationStatus.SETTLED
        }
}
