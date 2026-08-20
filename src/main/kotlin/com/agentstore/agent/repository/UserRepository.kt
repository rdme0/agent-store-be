package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {
    fun findByExternalId(externalId: String): User?
}
