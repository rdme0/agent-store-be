package com.agentstore.payment.dto.internal

import java.time.Instant

data class KrwEstimateDto(
    val amountWon: String,
    val rateWonPerUsdc: String,
    val rateAsOf: Instant,
    val stale: Boolean,
)
