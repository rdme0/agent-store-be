package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.Agent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface AgentRepository : JpaRepository<Agent, UUID> {
    fun findBySlug(slug: String): Agent?

    fun findAllByOrderByCreatedAtDesc(): List<Agent>
}
