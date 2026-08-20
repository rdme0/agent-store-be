package com.agentstore.dependency.resolver

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.ApiException
import com.agentstore.dependency.dto.response.QuoteWarning
import com.agentstore.dependency.model.vo.ResolvedEdge
import com.agentstore.dependency.model.vo.ResolvedGraph
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.model.vo.ResolvedVersion
import com.agentstore.dependency.repository.AgentDependencyRepository
import org.springframework.stereotype.Component
import java.util.*

@Component
class DependencyResolver(
    private val agentService: AgentService,
    private val dependencyRepository: AgentDependencyRepository,
) {
    fun resolve(
        rootVersionId: UUID,
        allowUnresolvedRequired: Boolean = false,
        allowPriceExceeded: Boolean = false
    ): ResolvedGraph {
        val root = agentService.requireVersion(rootVersionId).also {
            if (it.status != AgentVersionStatus.ACTIVE) {
                throw ApiException(
                    "AGENT_VERSION_NOT_FOUND",
                    "Agent version was not found",
                    404,
                    mapOf("id" to rootVersionId)
                )
            }
        }
        val warnings = mutableListOf<QuoteWarning>()
        return ResolvedGraph(
            resolveNode(root, emptyList(), warnings, 0, allowUnresolvedRequired, allowPriceExceeded),
            warnings
        )
    }

    fun validateConstraint(constraint: String) {
        if (constraint.isBlank() || constraint != "*" && !SEMVER.matches(constraint) && !CARET.matches(constraint) && !TILDE.matches(
                constraint
            )
        ) {
            throw ApiException(
                "INVALID_VERSION_CONSTRAINT",
                "versionConstraint is not a valid semver range",
                400,
                mapOf("versionConstraint" to constraint)
            )
        }
    }

    private fun resolveNode(
        version: AgentVersion,
        path: List<String>,
        warnings: MutableList<QuoteWarning>,
        depth: Int,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean
    ): ResolvedNode {
        val agent = agentService.requireAgent(version.agentId)
        if (depth > 4) {
            throw ApiException(
                "DEPENDENCY_DEPTH_EXCEEDED",
                "Dependency graph exceeds the maximum depth",
                422,
                mapOf("maxDepth" to 5, "agent" to agent.slug)
            )
        }
        val currentPath = path + agent.slug
        val edges = dependencyRepository.findAllBySourceVersionId(version.id).map { dependency ->
            validateConstraint(dependency.versionConstraint)
            val target = agentService.requireAgent(dependency.targetAgentId)
            val candidates = agentService.activeVersions(dependency.targetAgentId)
            val selected = candidates.filter { matches(it.semver, dependency.versionConstraint) }
                .maxByOrNull { versionKey(it.semver) }
            if (selected == null) {
                if (dependency.isRequired && !allowUnresolvedRequired) {
                    throw ApiException(
                        "DEPENDENCY_NOT_RESOLVED",
                        "A required dependency could not be resolved",
                        409,
                        mapOf("dependencyId" to dependency.id)
                    )
                }
                warnings += QuoteWarning(
                    "OPTIONAL_DEPENDENCY_NOT_RESOLVED",
                    dependency.id,
                    dependency.targetAgentId,
                    target.slug,
                    dependency.versionConstraint
                )
                return@map ResolvedEdge(dependency, target.slug, null)
            }
            if (target.slug in currentPath) {
                val cycle = currentPath.dropWhile { it != target.slug } + target.slug
                throw ApiException(
                    "DEPENDENCY_CYCLE_DETECTED",
                    "Dependency cycle detected",
                    409,
                    mapOf("cycle" to cycle)
                )
            }
            if (!allowPriceExceeded && selected.priceAtomic > dependency.maxPriceAtomic) {
                throw ApiException(
                    "DEPENDENCY_PRICE_EXCEEDED",
                    "Resolved dependency price exceeds maxPriceAtomic",
                    422,
                    mapOf(
                        "dependencyId" to dependency.id,
                        "priceAtomic" to selected.priceAtomic.toString(),
                        "maxPriceAtomic" to dependency.maxPriceAtomic.toString()
                    )
                )
            }
            ResolvedEdge(
                dependency,
                target.slug,
                resolveNode(selected, currentPath, warnings, depth + 1, allowUnresolvedRequired, allowPriceExceeded)
            )
        }
        return ResolvedNode(toResolvedVersion(version, agent.slug), edges)
    }

    private fun toResolvedVersion(version: AgentVersion, slug: String): ResolvedVersion {
        return ResolvedVersion(
            version.id,
            version.agentId,
            slug,
            version.semver,
            version.endpoint,
            version.priceAtomic,
            version.network,
            version.asset,
            version.payTo
        )
    }

    private fun matches(version: String, constraint: String): Boolean {
        val value = versionKey(version)
        return when {
            constraint == "*" -> true
            constraint.startsWith("^") -> value >= versionKey(constraint.drop(1)) && version.substringBefore('.') == constraint.drop(
                1
            ).substringBefore('.')

            constraint.startsWith("~") -> value >= versionKey(constraint.drop(1)) && version.substringBeforeLast('.') == constraint.drop(
                1
            ).substringBeforeLast('.')

            else -> version == constraint
        }
    }

    private fun versionKey(value: String): Long {
        val parts = value.removePrefix("^").removePrefix("~").split(".")
        return parts[0].toLong() * 1_000_000L + parts[1].toLong() * 1_000L + parts[2].takeWhile { it.isDigit() }
            .toLong()
    }

    companion object {
        private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val CARET = Regex("^\\^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val TILDE = Regex("^~(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
    }
}
