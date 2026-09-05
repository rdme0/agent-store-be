package com.agentstore.payment.dto.internal

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration

data class PaymentInvocationRequestDto(
    val paymentAttemptId: String,
    val idempotencyKey: String,
    val invocationToken: String,
    val endpoint: String,
    val amountAtomic: String,
    val maxPriceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    val body: Any?,
    val invocationDeadline: Duration,
)

data class PaymentInvocationResultDto(
    val output: JsonNode,
    val transactionHash: String,
    val paymentIdentifier: String? = null,
    val agentStatus: Int = 200,
)
