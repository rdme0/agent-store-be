package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.Agent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.EntityGraph
import java.util.UUID

interface AgentRepository : JpaRepository<Agent, UUID> {
    @EntityGraph(attributePaths = ["developer", "versions"])
    fun findBySlug(slug: String): Agent?

    @EntityGraph(attributePaths = ["developer", "versions"])
    fun findAllByOrderByCreatedAtDesc(): List<Agent>
}
