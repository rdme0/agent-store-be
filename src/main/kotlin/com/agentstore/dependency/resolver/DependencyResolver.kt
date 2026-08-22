package com.agentstore.dependency.resolver

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.response.QuoteWarning
import com.agentstore.dependency.exception.DependencyCycleDetectedException
import com.agentstore.dependency.model.vo.ResolvedEdge
import com.agentstore.dependency.model.vo.ResolvedGraph
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.model.vo.ResolvedVersion
import com.agentstore.dependency.repository.AgentDependencyRepository
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class DependencyResolver(
    private val agentService: AgentService,
    private val dependencyRepository: AgentDependencyRepository,
) {
    companion object {
        private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val CARET = Regex("^\\^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val TILDE = Regex("^~(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
    }

    fun resolve(
        rootVersionId: UUID,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean,
    ): ResolvedGraph {
        val root = agentService.requireVersion(rootVersionId).also {
            if (it.status != AgentVersionStatus.ACTIVE) {
                throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
            }
        }
        val warnings = mutableListOf<QuoteWarning>()
        return ResolvedGraph(
            root = resolveNode(
                version = root,
                path = emptyList(),
                warnings = warnings,
                depth = 0,
                allowUnresolvedRequired = allowUnresolvedRequired,
                allowPriceExceeded = allowPriceExceeded,
            ),
            warnings = warnings,
        )
    }

    fun validateConstraint(constraint: String) {
        if (constraint.isBlank() || constraint != "*" && !SEMVER.matches(constraint) && !CARET.matches(
                constraint
            ) && !TILDE.matches(
                constraint
            )
        ) {
            throw DomainClientException(ErrorCode.INVALID_VERSION_CONSTRAINT)
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
            throw DomainClientException(ErrorCode.DEPENDENCY_DEPTH_EXCEEDED)
        }
        val currentPath = path + agent.slug
        val edges = dependencyRepository.findAllBySourceVersionId(version.id).map { dependency ->
            validateConstraint(dependency.versionConstraint)
            val target = agentService.requireAgent(dependency.targetAgentId)
            val candidates = agentService.activeVersions(dependency.targetAgentId)
            val selected = candidates.filter { candidate ->
                matches(version = candidate.semver, constraint = dependency.versionConstraint)
            }
                .maxByOrNull { versionKey(it.semver) }
            if (selected == null) {
                if (dependency.isRequired && !allowUnresolvedRequired) {
                    throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
                }
                warnings += QuoteWarning(
                    code = "OPTIONAL_DEPENDENCY_NOT_RESOLVED",
                    dependencyId = dependency.id,
                    targetAgentId = dependency.targetAgentId,
                    targetAgentSlug = target.slug,
                    versionConstraint = dependency.versionConstraint,
                )
                return@map ResolvedEdge(
                    dependency = dependency,
                    targetAgentSlug = target.slug,
                    resolved = null,
                )
            }
            if (target.slug in currentPath) {
                val cycle = currentPath.dropWhile { it != target.slug } + target.slug
                throw DependencyCycleDetectedException(cycle.joinToString(" -> "))
            }
            if (!allowPriceExceeded && selected.priceAtomic > dependency.maxPriceAtomic) {
                throw DomainClientException(ErrorCode.DEPENDENCY_PRICE_EXCEEDED)
            }
            ResolvedEdge(
                dependency = dependency,
                targetAgentSlug = target.slug,
                resolved = resolveNode(
                    version = selected,
                    path = currentPath,
                    warnings = warnings,
                    depth = depth + 1,
                    allowUnresolvedRequired = allowUnresolvedRequired,
                    allowPriceExceeded = allowPriceExceeded,
                ),
            )
        }
        return ResolvedNode(
            version = toResolvedVersion(version = version, slug = agent.slug),
            dependencies = edges,
        )
    }

    private fun toResolvedVersion(version: AgentVersion, slug: String): ResolvedVersion {
        return ResolvedVersion(
            id = version.id,
            agentId = version.agentId,
            agentSlug = slug,
            semver = version.semver,
            endpoint = version.endpoint,
            priceAtomic = version.priceAtomic,
            network = version.network,
            asset = version.asset,
            payTo = version.payTo,
            responseFormat = version.responseFormat,
        )
    }

    private fun matches(version: String, constraint: String): Boolean {
        val value = versionKey(version)
        return when {
            constraint == "*" -> true
            constraint.startsWith("^") -> value >= versionKey(constraint.drop(1)) && version.substringBefore(
                '.'
            ) == constraint.drop(
                1
            ).substringBefore('.')

            constraint.startsWith("~") -> value >= versionKey(constraint.drop(1)) && version.substringBeforeLast(
                '.'
            ) == constraint.drop(
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

}
