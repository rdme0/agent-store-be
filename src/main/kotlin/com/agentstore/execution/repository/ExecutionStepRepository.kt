package com.agentstore.execution.repository

import com.agentstore.execution.model.entity.ExecutionStep
import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ExecutionStepRepository : JpaRepository<ExecutionStep, UUID> {
    fun findAllByExecutionIdOrderByCreatedAtAsc(executionId: UUID): List<ExecutionStep>
    fun findByParentStepIdAndIdempotencyKey(
        parentStepId: UUID,
        idempotencyKey: String
    ): ExecutionStep?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExecutionStep s where s.id = :id")
    fun findByIdForUpdate(id: UUID): ExecutionStep?
}
