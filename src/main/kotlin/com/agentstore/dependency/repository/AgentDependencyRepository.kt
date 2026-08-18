package com.agentstore.dependency.repository

import com.agentstore.dependency.model.entity.AgentDependency
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentDependencyRepository : JpaRepository<AgentDependency, UUID> {
    fun findAllBySourceVersionId(sourceVersionId: UUID): List<AgentDependency>
}
