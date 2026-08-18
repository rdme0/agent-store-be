package com.agentstore.revenue.service

import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.revenue.model.entity.RevenueEntry
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.repository.RevenueEntryRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class RevenueSettlementService(
    private val revenueEntryRepository: RevenueEntryRepository,
    private val agentVersionRepository: AgentVersionRepository,
    private val agentRepository: AgentRepository,
    private val executionStepService: ExecutionStepService,
) {
    @Transactional
    fun record(attempt: PaymentAttempt, type: RevenueType): RevenueEntry {
        revenueEntryRepository.findByPaymentAttemptId(attempt.id)?.let { return it }
        val agentVersionId = executionStepService.agentVersionId(attempt.executionStepId)
        val stepVersion = agentVersionId?.let { agentVersionRepository.findById(it).orElse(null) }
        val developerId = stepVersion?.let { agentRepository.findById(it.agentId).orElse(null)?.developerId }
            ?: error("revenue_agent_owner_not_found")
        return revenueEntryRepository.save(RevenueEntry(UUID.randomUUID(), developerId, attempt.executionStepId, attempt.id, type, attempt.amountAtomic, attempt.paymentMode, attempt.transactionHash, attempt.paymentIdentifier))
    }
}
