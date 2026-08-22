package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.Developer
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface DeveloperRepository : JpaRepository<Developer, UUID>
