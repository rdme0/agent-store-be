package com.agentstore.revenue.repository

import com.agentstore.revenue.model.entity.RevenueEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.EntityGraph
import java.util.UUID

interface RevenueEntryRepository : JpaRepository<RevenueEntry, UUID> {
    @EntityGraph(attributePaths = ["executionStep", "paymentAttempt"])
    fun findAllByDeveloperIdOrderByCreatedAtDesc(developerId: UUID): List<RevenueEntry>
}
