package com.agentstore.dependency.resolver

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.web.ApiException
import com.agentstore.dependency.dto.QuoteWarning
import com.agentstore.dependency.model.vo.ResolvedEdge
import com.agentstore.dependency.model.vo.ResolvedGraph
import com.agentstore.dependency.model.vo.ResolvedNode
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DependencyResolver(private val agentVersionRepository: AgentVersionRepository) {
    fun resolve(rootVersionId: UUID, allowUnresolvedRequired: Boolean = false, allowPriceExceeded: Boolean = false): ResolvedGraph {
        val root = agentVersionRepository.findByIdAndStatus(rootVersionId, AgentVersionStatus.ACTIVE)
            ?: throw ApiException("AGENT_VERSION_NOT_FOUND", "Agent version was not found", 404, mapOf("id" to rootVersionId))
        val warnings = mutableListOf<QuoteWarning>()
        val resolved = resolveNode(root, emptyList(), warnings, 0, allowUnresolvedRequired, allowPriceExceeded)
        return ResolvedGraph(resolved, warnings)
    }

    fun validateConstraint(constraint: String) {
        if (constraint.isBlank() || constraint != "*" && !SEMVER.matches(constraint) && !CARET.matches(constraint) && !TILDE.matches(constraint)) {
            throw ApiException("INVALID_VERSION_CONSTRAINT", "versionConstraint is not a valid semver range", 400, mapOf("versionConstraint" to constraint))
        }
    }

    private fun resolveNode(
        version: AgentVersion,
        path: List<String>,
        warnings: MutableList<QuoteWarning>,
        depth: Int,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean,
    ): ResolvedNode {
        if (depth > 4) throw ApiException("DEPENDENCY_DEPTH_EXCEEDED", "Dependency graph exceeds the maximum depth", 422, mapOf("maxDepth" to 5, "agent" to version.agent.slug))
        val currentPath = path + version.agent.slug
        val edges = version.dependencies.map { dependency ->
            validateConstraint(dependency.versionConstraint)
            val candidates = agentVersionRepository.findAllByAgentIdAndStatus(dependency.targetAgent.id, AgentVersionStatus.ACTIVE)
            val selected = candidates.filter { matches(it.semver, dependency.versionConstraint) }.maxByOrNull { versionKey(it.semver) }
            if (selected == null) {
                if (dependency.isRequired && !allowUnresolvedRequired) throw ApiException("DEPENDENCY_NOT_RESOLVED", "A required dependency could not be resolved", 409, mapOf("dependencyId" to dependency.id))
                warnings += QuoteWarning("OPTIONAL_DEPENDENCY_NOT_RESOLVED", dependency.id, dependency.targetAgent.id, dependency.targetAgent.slug, dependency.versionConstraint)
                return@map ResolvedEdge(dependency, null)
            }
            if (selected.agent.slug in currentPath) {
                val cycle = currentPath.dropWhile { it != selected.agent.slug } + selected.agent.slug
                throw ApiException("DEPENDENCY_CYCLE_DETECTED", "Dependency cycle detected", 409, mapOf("cycle" to cycle))
            }
            if (!allowPriceExceeded && selected.priceAtomic > dependency.maxPriceAtomic) throw ApiException("DEPENDENCY_PRICE_EXCEEDED", "Resolved dependency price exceeds maxPriceAtomic", 422, mapOf("dependencyId" to dependency.id, "priceAtomic" to selected.priceAtomic.toString(), "maxPriceAtomic" to dependency.maxPriceAtomic.toString()))
            ResolvedEdge(dependency, resolveNode(selected, currentPath, warnings, depth + 1, allowUnresolvedRequired, allowPriceExceeded))
        }
        return ResolvedNode(version, edges)
    }

    private fun matches(version: String, constraint: String): Boolean {
        val value = parse(version)
        return when {
            constraint == "*" -> true
            constraint.startsWith("^") -> versionKey(version) >= versionKey(constraint.drop(1)) && value.first == parse(constraint.drop(1)).first
            constraint.startsWith("~") -> versionKey(version) >= versionKey(constraint.drop(1)) && value.first == parse(constraint.drop(1)).first && value.second == parse(constraint.drop(1)).second
            else -> version == constraint
        }
    }

    private fun parse(value: String): Triple<Int, Int, Int> {
        val parts = value.removePrefix("^").removePrefix("~").split(".")
        return Triple(parts[0].toInt(), parts[1].toInt(), parts[2].takeWhile { it.isDigit() }.toInt())
    }

    private fun versionKey(value: String): Long {
        val parsed = parse(value)
        return parsed.first.toLong() * 1_000_000L + parsed.second * 1_000L + parsed.third
    }

    companion object {
        private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val CARET = Regex("^\\^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val TILDE = Regex("^~(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
    }
}
