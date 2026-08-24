package com.agentstore.payment.dto.response

import com.agentstore.payment.dto.internal.KrwEstimateDto
import java.time.Instant

data class KrwEstimateResponse(
    val amountWon: String,
    val rateWonPerUsdc: String,
    val rateAsOf: Instant,
    val stale: Boolean,
) {
    companion object {
        fun from(estimate: KrwEstimateDto): KrwEstimateResponse {
            return KrwEstimateResponse(
                amountWon = estimate.amountWon,
                rateWonPerUsdc = estimate.rateWonPerUsdc,
                rateAsOf = estimate.rateAsOf,
                stale = estimate.stale,
            )
        }
    }
}
