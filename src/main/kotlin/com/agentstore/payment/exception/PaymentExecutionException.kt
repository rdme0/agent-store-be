package com.agentstore.payment.exception

class PaymentExecutionException(
    val failureCode: String,
    cause: Throwable,
) : RuntimeException(failureCode, cause)
