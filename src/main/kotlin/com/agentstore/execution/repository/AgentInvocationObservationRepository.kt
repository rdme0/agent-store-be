package com.agentstore.execution.repository

import com.agentstore.execution.model.entity.AgentInvocationObservation
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface AgentInvocationObservationRepository : JpaRepository<AgentInvocationObservation, UUID> {
    fun findByExecutionStepId(executionStepId: UUID): AgentInvocationObservation?

    fun findAllByFunctionContractIdAndCompletedAtGreaterThanEqual(
        functionContractId: UUID,
        completedAt: Instant,
    ): List<AgentInvocationObservation>
}
