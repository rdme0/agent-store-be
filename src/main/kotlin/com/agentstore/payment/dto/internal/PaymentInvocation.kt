package com.agentstore.payment.dto.internal

import com.fasterxml.jackson.databind.JsonNode

data class PaymentInvocationRequest(val endpoint: String, val amountAtomic: String, val body: Any?)
data class PaymentInvocationResult(val output: JsonNode, val transactionHash: String? = null, val paymentIdentifier: String? = null)
