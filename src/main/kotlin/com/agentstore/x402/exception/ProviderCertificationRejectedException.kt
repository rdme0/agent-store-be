package com.agentstore.x402.exception

class ProviderCertificationRejectedException(
    val failureCode: String,
    val paymentSettled: Boolean = false,
) : RuntimeException(failureCode)
