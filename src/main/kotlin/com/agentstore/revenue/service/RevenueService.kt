package com.agentstore.revenue.service

import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.revenue.dto.response.DeveloperRevenueResponse
import com.agentstore.revenue.dto.response.RevenueEntryResponse
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.repository.RevenueEntryRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.*

@Service
class RevenueService(
    private val agentService: AgentService,
    private val revenueRepository: RevenueEntryRepository,
) {
    @Transactional
    fun get(developerId: UUID, cursor: UUID?, limit: Int): DeveloperRevenueResponse {
        if (limit !in 1..100) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        if (!agentService.developerExists(developerId)) {
            throw DomainClientException(ErrorCode.DEVELOPER_NOT_FOUND)
        }
        val all = revenueRepository.findAllByDeveloperIdOrderByCreatedAtDesc(developerId)
        val start = if (cursor == null) {
            0
        } else {
            val index = all.indexOfFirst { it.id == cursor }
            if (index < 0) {
                throw DomainClientException(ErrorCode.INVALID_CURSOR)
            }
            index + 1
        }
        val page = all.drop(start).take(limit + 1)
        val direct = all.filter { it.type == RevenueType.DIRECT }
        val dependency = all.filter { it.type == RevenueType.DEPENDENCY }
        fun sum(entries: List<com.agentstore.revenue.model.entity.RevenueEntry>): BigInteger {
            return entries.fold(BigInteger.ZERO) { total, entry -> total + entry.amountAtomic }
        }
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
