package com.agentstore.dependency.resolver

import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.ApiException
import com.agentstore.dependency.repository.AgentDependencyRepository
import org.springframework.stereotype.Component
import java.util.*

@Component
class CycleValidator(
    private val agentService: AgentService,
    private val dependencyRepository: AgentDependencyRepository,
) {
    fun validate(sourceAgentId: UUID, targetAgentId: UUID, sourceSlug: String, targetSlug: String) {
        if (sourceAgentId == targetAgentId) {
            throw cycle(listOf(sourceSlug, targetSlug))
        }
        val path = mutableListOf(sourceSlug)
        if (reachable(targetAgentId, sourceAgentId, mutableSetOf(), path)) {
            throw cycle(path + sourceSlug)
        }
    }

    private fun reachable(
        currentAgentId: UUID,
        targetAgentId: UUID,
        visited: MutableSet<UUID>,
        path: MutableList<String>
    ): Boolean {
        if (!visited.add(currentAgentId)) {
            return false
        }
        val agent = agentService.findByIdOrNull(currentAgentId) ?: return false
        path += agent.slug
        val versions = agentService.draftOrActiveVersions(currentAgentId)
        val found = versions.any { version ->
            dependencyRepository.findAllBySourceVersionId(version.id).any { dependency ->
                dependency.targetAgentId == targetAgentId || reachable(
                    dependency.targetAgentId,
                    targetAgentId,
                    visited,
                    path
                )
            }
        }
        if (!found) {
            path.removeAt(path.lastIndex)
        }
        return found
    }

    private fun cycle(path: List<String>): ApiException {
        return ApiException("DEPENDENCY_CYCLE_DETECTED", "Dependency cycle detected", 409, mapOf("cycle" to path))
    }
}
