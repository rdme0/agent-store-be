package com.agentstore.dependency.resolver

import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.web.ApiException
import com.agentstore.dependency.repository.AgentDependencyRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CycleValidator(
    private val agentRepository: AgentRepository,
    private val agentVersionRepository: AgentVersionRepository,
    private val dependencyRepository: AgentDependencyRepository,
) {
    fun validate(sourceAgentId: UUID, targetAgentId: UUID, sourceSlug: String, targetSlug: String) {
        if (sourceAgentId == targetAgentId) throw cycle(listOf(sourceSlug, targetSlug))
        val path = mutableListOf(sourceSlug)
        if (reachable(targetAgentId, sourceAgentId, mutableSetOf(), path)) throw cycle(path + sourceSlug)
    }

    private fun reachable(currentAgentId: UUID, targetAgentId: UUID, visited: MutableSet<UUID>, path: MutableList<String>): Boolean {
        if (!visited.add(currentAgentId)) return false
        val agent = agentRepository.findById(currentAgentId).orElse(null) ?: return false
        path += agent.slug
        val versions = agentVersionRepository.findAllByAgentIdAndStatus(currentAgentId, AgentVersionStatus.ACTIVE)
            .ifEmpty { agentVersionRepository.findAllByAgentIdAndStatus(currentAgentId, AgentVersionStatus.DRAFT) }
        val found = versions.any { version ->
            dependencyRepository.findAllBySourceVersionId(version.id).any { dependency ->
                dependency.targetAgentId == targetAgentId || reachable(dependency.targetAgentId, targetAgentId, visited, path)
            }
        }
        if (!found) path.removeAt(path.lastIndex)
        return found
    }

    private fun cycle(path: List<String>) = ApiException("DEPENDENCY_CYCLE_DETECTED", "Dependency cycle detected", 409, mapOf("cycle" to path))
}
