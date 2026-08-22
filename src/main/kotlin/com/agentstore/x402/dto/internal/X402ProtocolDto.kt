package com.agentstore.x402.dto.internal

import com.fasterxml.jackson.databind.node.ObjectNode

data class X402PaymentRequiredDto(
    val resource: ObjectNode,
    val selected: ObjectNode,
    val maxTimeoutSeconds: Long,
    val tokenName: String,
    val tokenVersion: String,
    val extensions: ObjectNode?,
)

data class X402AuthorizationDto(
    val from: String,
    val to: String,
    val value: String,
    val validAfter: String,
    val validBefore: String,
    val nonce: String,
)

data class X402SettlementReceiptDto(
    val success: Boolean,
    val transaction: String?,
    val network: String?,
)
