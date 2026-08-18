package com.agentstore.payment.dto.internal

import com.fasterxml.jackson.databind.JsonNode

data class PaymentInvocationRequest(
    val paymentAttemptId: String,
    val idempotencyKey: String,
    val invocationToken: String,
    val endpoint: String,
    val amountAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    val body: Any?,
)
data class PaymentInvocationResult(val output: JsonNode, val transactionHash: String? = null, val paymentIdentifier: String? = null)
