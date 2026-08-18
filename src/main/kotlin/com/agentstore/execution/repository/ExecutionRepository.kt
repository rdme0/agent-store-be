package com.agentstore.execution.repository

import com.agentstore.execution.model.entity.Execution
import com.agentstore.execution.model.vo.ExecutionStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ExecutionRepository : JpaRepository<Execution, UUID> {
    fun findAllByStatusIn(statuses: Collection<ExecutionStatus>): List<Execution>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Execution e where e.id = :id")
    fun findByIdForUpdate(id: UUID): Execution?
}
