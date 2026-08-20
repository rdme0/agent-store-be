package com.agentstore.payment.dto.internal

data class X402BridgePaymentRequest(
    val paymentAttemptId: String,
    val idempotencyKey: String,
    val amountAtomic: String,
    val maxPriceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    val endpoint: String,
    val method: String = "POST",
    val headers: Map<String, String>,
    val body: String?
)

data class X402BridgePaymentResponse(
    val outcome: String,
    val code: String? = null,
    val message: String? = null,
    val transactionHash: String? = null,
    val paymentIdentifier: String? = null,
    val response: X402BridgeAgentResponse? = null
)

data class X402BridgeAgentResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String)
