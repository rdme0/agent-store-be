package com.agentstore.payment.dto.internal

import com.agentstore.payment.model.vo.PaymentReconciliationStatus

data class PaymentReconciliationResultDto(
    val status: PaymentReconciliationStatus,
    val transactionHash: String? = null,
    val paymentIdentifier: String? = null,
)
