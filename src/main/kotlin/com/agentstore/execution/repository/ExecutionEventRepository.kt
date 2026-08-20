package com.agentstore.execution.repository

import com.agentstore.execution.model.entity.ExecutionEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.*

interface ExecutionEventRepository : JpaRepository<ExecutionEvent, UUID> {
    @Query("select e from ExecutionEvent e where e.executionId = :executionId and e.sequence > :after order by e.sequence asc")
    fun findReplay(executionId: UUID, after: Int): List<ExecutionEvent>

    fun findFirstByExecutionIdOrderBySequenceDesc(executionId: UUID): ExecutionEvent?
}
