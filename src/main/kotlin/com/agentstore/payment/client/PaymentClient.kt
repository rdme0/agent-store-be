package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.payment.dto.internal.PaymentInvocationResultDto
import com.agentstore.payment.model.vo.PaymentMode

interface PaymentClient {
    val mode: PaymentMode
    fun invoke(request: PaymentInvocationRequestDto): PaymentInvocationResultDto
}
