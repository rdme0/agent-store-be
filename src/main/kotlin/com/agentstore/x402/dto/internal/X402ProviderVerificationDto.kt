package com.agentstore.x402.dto.internal

import com.fasterxml.jackson.databind.JsonNode

data class X402ProviderVerificationRequestDto(
    val endpoint: String,
    val amountAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    val input: JsonNode,
)

data class X402ProviderCertificationResultDto(
    val output: JsonNode,
    val transactionHash: String,
)
