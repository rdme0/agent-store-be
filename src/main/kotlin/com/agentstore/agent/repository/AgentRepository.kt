package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.Agent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentRepository : JpaRepository<Agent, UUID> {
    fun findBySlug(slug: String): Agent?
}
