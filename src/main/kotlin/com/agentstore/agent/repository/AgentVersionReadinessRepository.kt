package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.AgentVersionReadiness
import com.agentstore.agent.model.vo.AgentVersionReadinessStatus
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType

interface AgentVersionReadinessRepository : JpaRepository<AgentVersionReadiness, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select readiness from AgentVersionReadiness readiness where readiness.versionId = :versionId")
    fun findLockedByVersionId(versionId: UUID): AgentVersionReadiness?

    fun findAllByStatus(status: AgentVersionReadinessStatus): List<AgentVersionReadiness>
}
