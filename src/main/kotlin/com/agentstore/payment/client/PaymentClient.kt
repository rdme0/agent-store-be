package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult

interface PaymentClient {
    fun invoke(request: PaymentInvocationRequest): PaymentInvocationResult
}
