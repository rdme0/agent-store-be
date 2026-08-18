package com.agentstore.dependency.resolver

import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.web.ApiException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CycleValidator(private val agentVersionRepository: AgentVersionRepository) {
    fun validate(sourceAgentId: UUID, targetAgentId: UUID, sourceSlug: String, targetSlug: String) {
        if (sourceAgentId == targetAgentId) {
            throw ApiException("DEPENDENCY_CYCLE_DETECTED", "Dependency cycle detected", 409, mapOf("cycle" to listOf(sourceSlug, targetSlug)))
        }
        val path = mutableListOf(sourceSlug)
        if (reachable(targetAgentId, sourceAgentId, mutableSetOf(), path)) {
            val cycleStart = path.indexOfFirst { it == sourceSlug }.let { if (it < 0) 0 else it }
            throw ApiException("DEPENDENCY_CYCLE_DETECTED", "Dependency cycle detected", 409, mapOf("cycle" to (path.drop(cycleStart) + sourceSlug)))
        }
    }

    private fun reachable(currentAgentId: UUID, targetAgentId: UUID, visited: MutableSet<UUID>, path: MutableList<String>): Boolean {
        if (!visited.add(currentAgentId)) return false
        val versions = agentVersionRepository.findAllByAgentIdAndStatus(currentAgentId, AgentVersionStatus.ACTIVE)
            .ifEmpty { agentVersionRepository.findAllByAgentIdAndStatus(currentAgentId, AgentVersionStatus.DRAFT) }
        val version = versions.maxByOrNull { it.semver } ?: return false
        path += version.agent.slug
        version.dependencies.forEach { dependency ->
            if (dependency.targetAgent.id == targetAgentId) return true
            if (reachable(dependency.targetAgent.id, targetAgentId, visited, path)) return true
        }
        path.removeAt(path.lastIndex)
        return false
    }
}
