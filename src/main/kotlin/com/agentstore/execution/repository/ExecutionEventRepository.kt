package com.agentstore.execution.repository

import com.agentstore.execution.model.entity.ExecutionEvent
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ExecutionEventRepository : JpaRepository<ExecutionEvent, UUID> {
    @Query(
        """
        select event
        from ExecutionEvent event
        where event.executionId = :executionId
          and event.sequence > :after
        order by event.sequence asc
        """,
    )
    fun findReplay(executionId: UUID, after: Int): List<ExecutionEvent>

    fun findFirstByExecutionIdOrderBySequenceDesc(executionId: UUID): ExecutionEvent?
}
