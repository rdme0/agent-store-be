package com.agentstore.external.dto.internal

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

data class ExternalPaymentRequirementDto(
    val resourceUrl: String,
    val amountAtomic: String,
    val payTo: String,
    val header: String,
    val requirement: ObjectNode,
    val expiresAt: Instant,
)

data class IncomingPaymentVerificationDto(
    val payer: String,
)

data class IncomingPaymentSettlementDto(
    val payer: String,
    val transactionHash: String,
)
