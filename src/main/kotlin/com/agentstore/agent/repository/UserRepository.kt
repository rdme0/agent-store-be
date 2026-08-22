package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.User
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, UUID> {
    fun findByExternalId(externalId: String): User?
}
