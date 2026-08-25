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
    fun validate(sourceAgentId: UUID, targetAgentId: UUID, sourceCode: String, targetCode: String) {
        if (sourceAgentId == targetAgentId) {
            throw cycle(listOf(sourceCode, targetCode))
        }
        val path = mutableListOf(sourceCode)
        if (
            reachable(
                currentAgentId = targetAgentId,
                targetAgentId = sourceAgentId,
                visited = mutableSetOf(),
                path = path,
            )
        ) {
            throw cycle(path + sourceCode)
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
        path += agent.code
        val versions = agentService.draftOrActiveVersions(currentAgentId)
        val found = versions.any { version ->
            dependencyRepository.findAllBySourceVersionIdOrderByIdAsc(version.id).any { dependency ->
                dependency.targetAgentId?.let { dependencyTargetAgentId ->
                    dependencyTargetAgentId == targetAgentId || reachable(
                        currentAgentId = dependencyTargetAgentId,
                        targetAgentId = targetAgentId,
                        visited = visited,
                        path = path,
                    )
                } ?: false
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
