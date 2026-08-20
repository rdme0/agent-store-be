package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult
import com.agentstore.payment.model.vo.PaymentMode

interface PaymentClient {
    val mode: PaymentMode
    fun invoke(request: PaymentInvocationRequest): PaymentInvocationResult
}
