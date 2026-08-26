package com.agentstore.revenue.service

import com.agentstore.agent.service.AgentService
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.revenue.model.entity.RevenueEntry
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.repository.RevenueEntryRepository
import jakarta.transaction.Transactional
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class RevenueSettlementService(
    private val revenueEntryRepository: RevenueEntryRepository,
    private val agentService: AgentService,
    private val executionStepService: ExecutionStepService,
) {
    @Transactional
    fun record(attempt: PaymentAttempt, type: RevenueType): RevenueEntry {
        revenueEntryRepository.findByPaymentAttemptId(attempt.id)?.let { return it }
        val agentVersionId = executionStepService.agentVersionId(attempt.executionStepId)
            ?: error("revenue_agent_version_not_found")
        val developerId = agentService.developerIdForVersion(agentVersionId)
        return revenueEntryRepository.save(
            RevenueEntry(
                UUID.randomUUID(),
                developerId,
                attempt.executionStepId,
                attempt.id,
                type,
                attempt.amountAtomic,
                attempt.transactionHash,
                attempt.paymentIdentifier
            )
        )
    }
}
