package com.agentstore.execution.repository

import com.agentstore.execution.model.entity.ExecutionStep
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExecutionStepRepository : JpaRepository<ExecutionStep, UUID> {
    fun findAllByExecutionIdOrderByCreatedAtAsc(executionId: UUID): List<ExecutionStep>
}
