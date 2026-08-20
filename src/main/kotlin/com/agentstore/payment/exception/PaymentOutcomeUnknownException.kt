package com.agentstore.payment.exception

/** A payment may have been signed or settled, so a caller must retain its reservation for reconciliation. */
class PaymentOutcomeUnknownException(
    val failureCode: String,
) : RuntimeException(failureCode)
