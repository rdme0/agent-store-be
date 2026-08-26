package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.payment.dto.internal.PaymentInvocationResultDto

interface PaymentClient {
    fun invoke(request: PaymentInvocationRequestDto): PaymentInvocationResultDto
}
