package com.agentstore.revenue.service

import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.common.web.ApiException
import com.agentstore.revenue.dto.response.DeveloperRevenueResponse
import com.agentstore.revenue.dto.response.RevenueEntryResponse
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.repository.RevenueEntryRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.UUID

@Service
class RevenueService(
    private val developerRepository: DeveloperRepository,
    private val revenueRepository: RevenueEntryRepository,
) {
    @Transactional
    fun get(developerId: UUID, cursor: UUID?, limit: Int): DeveloperRevenueResponse {
        if (limit !in 1..100) throw ApiException("VALIDATION_ERROR", "limit must be between 1 and 100", 422)
        if (!developerRepository.existsById(developerId)) throw ApiException("DEVELOPER_NOT_FOUND", "Developer was not found", 404)
        val all = revenueRepository.findAllByDeveloperIdOrderByCreatedAtDesc(developerId)
        val start = cursor?.let { id -> all.indexOfFirst { it.id == id }.also { if (it < 0) throw ApiException("INVALID_CURSOR", "Revenue cursor was not found", 400) } + 1 } ?: 0
        val page = all.drop(start).take(limit + 1)
        val direct = all.filter { it.type == RevenueType.DIRECT }
        val dependency = all.filter { it.type == RevenueType.DEPENDENCY }
        fun sum(entries: List<com.agentstore.revenue.model.entity.RevenueEntry>) = entries.fold(BigInteger.ZERO) { total, entry -> total + entry.amountAtomic }
        return DeveloperRevenueResponse(
            developerId = developerId,
            totalRevenueAtomic = sum(all).toString(),
            directRevenueAtomic = sum(direct).toString(),
            dependencyRevenueAtomic = sum(dependency).toString(),
            directCount = direct.size,
            dependencyCount = dependency.size,
            entries = page.take(limit).map(RevenueEntryResponse::from),
            nextCursor = page.getOrNull(limit)?.id,
        )
    }
}
