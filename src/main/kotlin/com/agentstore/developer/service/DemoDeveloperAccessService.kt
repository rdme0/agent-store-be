package com.agentstore.developer.service

import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.security.dto.DemoDeveloperPrincipal
import com.agentstore.developer.dto.response.DemoDeveloperResponse
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class DemoDeveloperAccessService(
    private val agentService: AgentService,
) {
    fun me(principal: DemoDeveloperPrincipal): DemoDeveloperResponse {
        val developer = agentService.requireDeveloper(principal.developerId)
        return DemoDeveloperResponse(id = developer.id, displayName = developer.displayName)
    }

    fun ownedAgents(principal: DemoDeveloperPrincipal): List<AgentResponse> {
        return agentService.listOwnedByDeveloper(developerId = principal.developerId)
    }

    fun requireAgentOwner(agentId: UUID, principal: DemoDeveloperPrincipal) {
        requireOwner(ownerId = agentService.developerIdForAgent(agentId), principal = principal)
    }

    fun requireVersionOwner(versionId: UUID, principal: DemoDeveloperPrincipal) {
        requireOwner(ownerId = agentService.developerIdForVersion(versionId), principal = principal)
    }

    fun requireOwner(ownerId: UUID, principal: DemoDeveloperPrincipal) {
        if (ownerId != principal.developerId) {
            throw DomainClientException(ErrorCode.DEMO_ACCESS_DENIED)
        }
    }
}
