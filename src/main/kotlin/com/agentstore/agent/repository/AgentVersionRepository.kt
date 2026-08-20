package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface AgentVersionRepository : JpaRepository<AgentVersion, UUID> {
    fun findWithAgentById(id: UUID): AgentVersion? {
        return findById(id).orElse(null)
    }

    fun findByAgentIdAndSemver(agentId: UUID, semver: String): AgentVersion?
    fun findAllByAgentId(agentId: UUID): List<AgentVersion>
    fun findAllByAgentIdAndStatus(agentId: UUID, status: AgentVersionStatus): List<AgentVersion>

    fun findByIdAndStatus(id: UUID, status: AgentVersionStatus): AgentVersion?

}
