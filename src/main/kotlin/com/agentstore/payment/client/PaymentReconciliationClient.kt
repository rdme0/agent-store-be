package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentReconciliationResultDto
import com.agentstore.payment.model.entity.PaymentAttempt

interface PaymentReconciliationClient {
    fun reconcile(attempt: PaymentAttempt): PaymentReconciliationResultDto
}
