package com.agentstore.agent.repository

import java.util.UUID

interface AgentDependencyCountProjection {
    val agentId: UUID
    val dependencyCount: Long
}
