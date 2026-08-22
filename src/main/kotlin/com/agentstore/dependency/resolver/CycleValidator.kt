package com.agentstore.dependency.resolver

import com.agentstore.agent.service.AgentService
import com.agentstore.dependency.exception.DependencyCycleDetectedException
import com.agentstore.dependency.repository.AgentDependencyRepository
import java.util.UUID
import org.springframework.stereotype.Component

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
        if (
            reachable(
                currentAgentId = targetAgentId,
                targetAgentId = sourceAgentId,
                visited = mutableSetOf(),
                path = path,
            )
        ) {
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
                    currentAgentId = dependency.targetAgentId,
                    targetAgentId = targetAgentId,
                    visited = visited,
                    path = path,
                )
            }
        }
        if (!found) {
            path.removeAt(path.lastIndex)
        }
        return found
    }

    private fun cycle(path: List<String>): DependencyCycleDetectedException {
        return DependencyCycleDetectedException(path.joinToString(" -> "))
    }
}
