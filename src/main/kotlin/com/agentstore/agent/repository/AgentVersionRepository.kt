package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentVersionRepository : JpaRepository<AgentVersion, UUID> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = ["agent", "agent.developer"])
    fun findWithAgentById(id: UUID): AgentVersion?

    fun findByAgentIdAndSemver(agentId: UUID, semver: String): AgentVersion?
    fun findAllByAgentIdAndStatus(agentId: UUID, status: AgentVersionStatus): List<AgentVersion>

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = ["agent", "agent.developer", "dependencies", "dependencies.targetAgent"])
    fun findByIdAndStatus(id: UUID, status: AgentVersionStatus): AgentVersion?

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = ["agent", "agent.developer", "dependencies", "dependencies.targetAgent"])
    fun findAllByAgentSlugAndStatus(slug: String, status: AgentVersionStatus): List<AgentVersion>
}
