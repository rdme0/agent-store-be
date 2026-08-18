package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentVersionRepository : JpaRepository<AgentVersion, UUID> {
    fun findByAgentIdAndSemver(agentId: UUID, semver: String): AgentVersion?
    fun findAllByAgentIdAndStatus(agentId: UUID, status: AgentVersionStatus): List<AgentVersion>
}
