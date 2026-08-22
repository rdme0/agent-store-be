package com.agentstore.revenue.dto.response

import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.revenue.model.entity.RevenueEntry
import com.agentstore.revenue.model.vo.RevenueType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class RevenueEntryResponse(
    val id: UUID,
    val executionStepId: UUID,
    val paymentAttemptId: UUID,
    @field:Schema(allowableValues = ["DIRECT", "DEPENDENCY"]) val type: RevenueType,
    @field:Schema(pattern = "^[0-9]+$") val amountAtomic: String,
    @field:Schema(allowableValues = ["simulated", "x402"]) val paymentMode: String,
    @field:Schema(nullable = false) val transactionHash: String? = null,
    @field:Schema(nullable = false) val paymentIdentifier: String? = null,
    val createdAt: Instant,
) {
    companion object {
        fun from(entry: RevenueEntry): RevenueEntryResponse {
            return RevenueEntryResponse(
                id = entry.id,
                executionStepId = entry.executionStepId,
                paymentAttemptId = entry.paymentAttemptId,
                type = entry.type,
                amountAtomic = entry.amountAtomic.toString(),
                paymentMode = if (entry.paymentMode == PaymentMode.X402) {
                    "x402"
                } else {
                    "simulated"
                },
                transactionHash = entry.transactionHash,
                paymentIdentifier = entry.paymentIdentifier,
                createdAt = entry.createdAt,
            )
        }
    }
}

data class DeveloperRevenueResponse(
    val developerId: UUID,
    @field:Schema(pattern = "^[0-9]+$") val totalRevenueAtomic: String,
    @field:Schema(pattern = "^[0-9]+$") val directRevenueAtomic: String,
    @field:Schema(pattern = "^[0-9]+$") val dependencyRevenueAtomic: String,
    @field:Schema(minimum = "0") val directCount: Int,
    @field:Schema(minimum = "0") val dependencyCount: Int,
    val entries: List<RevenueEntryResponse>,
    val nextCursor: UUID? = null,
)
