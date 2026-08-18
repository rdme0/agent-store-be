package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.Developer
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DeveloperRepository : JpaRepository<Developer, UUID>
