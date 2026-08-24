package com.agentstore.dependency.repository

import com.agentstore.dependency.model.entity.AgentDependencyAllowedProvider
import com.agentstore.dependency.model.entity.AgentDependencyAllowedProvider.AgentDependencyAllowedProviderId
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface AgentDependencyAllowedProviderRepository :
    JpaRepository<AgentDependencyAllowedProvider, AgentDependencyAllowedProviderId> {
    fun findAllByIdDependencyId(dependencyId: UUID): List<AgentDependencyAllowedProvider>

    fun deleteAllByIdDependencyId(dependencyId: UUID)
}
