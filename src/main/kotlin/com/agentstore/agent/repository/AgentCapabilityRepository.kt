package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.AgentCapability
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface AgentCapabilityRepository : JpaRepository<AgentCapability, UUID> {
    fun findByKeyAndContractVersion(key: String, contractVersion: String): AgentCapability?
    fun findAllByOrderByKeyAscContractVersionAsc(): List<AgentCapability>
}
