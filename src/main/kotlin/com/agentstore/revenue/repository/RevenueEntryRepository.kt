package com.agentstore.revenue.repository

import com.agentstore.revenue.model.entity.RevenueEntry
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface RevenueEntryRepository : JpaRepository<RevenueEntry, UUID> {
    fun findAllByDeveloperIdOrderByCreatedAtDesc(developerId: UUID): List<RevenueEntry>
    fun findByPaymentAttemptId(paymentAttemptId: UUID): RevenueEntry?
}
