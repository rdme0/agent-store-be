package com.agentstore.revenue.dto

import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.revenue.model.entity.RevenueEntry
import com.agentstore.revenue.model.vo.RevenueType
import java.time.Instant
import java.util.UUID

data class RevenueEntryResponse(
    val id: UUID,
    val executionStepId: UUID,
    val paymentAttemptId: UUID,
    val type: RevenueType,
    val amountAtomic: String,
    val paymentMode: String,
    val transactionHash: String? = null,
    val paymentIdentifier: String? = null,
    val createdAt: Instant,
) {
    companion object {
        fun from(entry: RevenueEntry) = RevenueEntryResponse(
            id = entry.id,
            executionStepId = entry.executionStep.id,
            paymentAttemptId = entry.paymentAttempt.id,
            type = entry.type,
            amountAtomic = entry.amountAtomic.toString(),
            paymentMode = if (entry.paymentMode == PaymentMode.X402) "x402" else "simulated",
            transactionHash = entry.transactionHash,
            paymentIdentifier = entry.paymentIdentifier,
            createdAt = entry.createdAt,
        )
    }
}

data class DeveloperRevenueResponse(
    val developerId: UUID,
    val totalRevenueAtomic: String,
    val directRevenueAtomic: String,
    val dependencyRevenueAtomic: String,
    val directCount: Int,
    val dependencyCount: Int,
    val entries: List<RevenueEntryResponse>,
    val nextCursor: UUID? = null,
)
