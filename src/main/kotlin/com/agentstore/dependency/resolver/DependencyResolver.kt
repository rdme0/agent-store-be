package com.agentstore.dependency.resolver

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.ClientException
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.response.QuoteWarning
import com.agentstore.dependency.exception.DependencyCycleDetectedException
import com.agentstore.dependency.model.entity.AgentDependency
import com.agentstore.dependency.model.vo.ProviderCandidate
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelection
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.model.vo.ResolvedFunctionContract
import com.agentstore.dependency.model.vo.ResolvedEdge
import com.agentstore.dependency.model.vo.ResolvedGraph
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.model.vo.ResolvedVersion
import com.agentstore.dependency.repository.AgentDependencyAllowedProviderRepository
import com.agentstore.dependency.repository.AgentDependencyRepository
import com.agentstore.execution.service.ProviderMetricService
import com.agentstore.execution.service.ProviderPerformanceDto
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import org.semver4j.Semver
import org.springframework.stereotype.Component

@Component
class DependencyResolver(
    private val agentService: AgentService,
    private val capabilityService: AgentCapabilityService,
    private val dependencyRepository: AgentDependencyRepository,
    private val allowedProviderRepository: AgentDependencyAllowedProviderRepository,
    private val providerMetricService: ProviderMetricService,
) {
    companion object {
        private const val MAX_PROVIDER_CANDIDATES = 50
        private const val MAX_PROVIDER_EXPLORATIONS = 500
        private const val SEMVER_COMPONENT = "(?:0|[1-9]\\d*)"
        private const val CONSTRAINT_VERSION = "$SEMVER_COMPONENT\\.$SEMVER_COMPONENT\\.$SEMVER_COMPONENT"
        private val CONSTRAINT_PREDICATE = Regex(
            "^\\s*(==|>=|>|<=|<)\\s*($CONSTRAINT_VERSION)\\s*$",
        )
        private val SKIPPABLE_CANDIDATE_ERRORS = setOf(
            ErrorCode.DEPENDENCY_NOT_RESOLVED,
            ErrorCode.DEPENDENCY_PRICE_EXCEEDED,
            ErrorCode.DEPENDENCY_CYCLE_DETECTED,
            ErrorCode.DEPENDENCY_DEPTH_EXCEEDED,
            ErrorCode.EXECUTION_STEPS_EXCEEDED,
        )
    }

    fun resolve(
        rootVersionId: UUID,
        selectionSeed: UUID,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean,
    ): ResolvedGraph {
        val root = agentService.requireVersion(rootVersionId).also { version ->
            if (version.status != AgentVersionStatus.ACTIVE) {
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
                stepBudget = 32,
                explorationBudget = ProviderExplorationBudget(MAX_PROVIDER_EXPLORATIONS),
                selectionSeed = selectionSeed,
                allowUnresolvedRequired = allowUnresolvedRequired,
                allowPriceExceeded = allowPriceExceeded,
            ),
            warnings = warnings,
        )
    }

    fun resolveFunctionRoot(
        functionCode: String,
        contractVersion: String,
        strategy: ProviderSelectionStrategy,
        maxPriceAtomic: BigInteger,
        selectionSeed: UUID,
    ): ResolvedGraph {
        val contract = capabilityService.requireByCode(
            code = functionCode,
            contractVersion = contractVersion,
        )
        val dependency = AgentDependency(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "*",
            true,
            maxPriceAtomic,
            1,
        )
        dependency.configureFunctionSelection(
            contract.id,
            ProviderScope.MARKETPLACE,
            strategy,
            null,
            null,
            0,
            null,
            null,
            null,
        )
        val warnings = mutableListOf<QuoteWarning>()
        val edges = resolveFunctionEdges(
            dependency = dependency,
            currentPath = emptyList(),
            warnings = warnings,
            depth = -1,
            remainingSteps = 32,
            explorationBudget = ProviderExplorationBudget(MAX_PROVIDER_EXPLORATIONS),
            selectionSeed = selectionSeed,
            allowUnresolvedRequired = false,
            allowPriceExceeded = false,
            resolveTail = { _, _ -> emptyList() },
        )
        val root = edges.single().resolved ?: throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
        return ResolvedGraph(root = root, warnings = warnings)
    }

    fun normalizeConstraint(constraint: String): String {
        val normalized = constraint.trim()
        if (normalized == "*") {
            return normalized
        }
        val predicates = normalized.split(',').map { predicate ->
            val match = CONSTRAINT_PREDICATE.matchEntire(predicate)
                ?: throw DomainClientException(ErrorCode.INVALID_VERSION_CONSTRAINT)
            VersionPredicate(operator = match.groupValues[1], version = match.groupValues[2])
        }
        if (predicates.isEmpty()) {
            throw DomainClientException(ErrorCode.INVALID_VERSION_CONSTRAINT)
        }
        return predicates.joinToString(separator = ",") { predicate ->
            "${predicate.operator}${predicate.version}"
        }
    }

    fun validateConstraint(constraint: String) {
        normalizeConstraint(constraint = constraint)
    }

    fun matches(version: String, constraint: String): Boolean {
        val normalized = normalizeConstraint(constraint = constraint)
        if (normalized == "*") {
            return true
        }
        val candidate = runCatching { Semver(version) }.getOrNull() ?: return false
        return normalized.split(',').all { predicate ->
            val match = requireNotNull(CONSTRAINT_PREDICATE.matchEntire(predicate))
            when (match.groupValues[1]) {
                "==" -> candidate.isEqualTo(match.groupValues[2])
                ">=" -> candidate.isGreaterThanOrEqualTo(match.groupValues[2])
                ">" -> candidate.isGreaterThan(match.groupValues[2])
                "<=" -> candidate.isLowerThanOrEqualTo(match.groupValues[2])
                "<" -> candidate.isLowerThan(match.groupValues[2])
                else -> error("validated version comparator is missing")
            }
        }
    }

    fun newest(versions: List<AgentVersion>): AgentVersion? {
        return versions.maxWithOrNull { left, right -> compareSemver(left.semver, right.semver) }
    }

    private data class VersionPredicate(val operator: String, val version: String)

    private fun resolveNode(
        version: AgentVersion,
        path: List<String>,
        warnings: MutableList<QuoteWarning>,
        depth: Int,
        stepBudget: Int,
        explorationBudget: ProviderExplorationBudget,
        selectionSeed: UUID,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean,
    ): ResolvedNode {
        val agent = agentService.requireAgent(version.agentId)
        if (depth > 4) {
            throw DomainClientException(ErrorCode.DEPENDENCY_DEPTH_EXCEEDED)
        }
        if (stepBudget < 1) {
            throw DomainClientException(ErrorCode.EXECUTION_STEPS_EXCEEDED)
        }
        val currentPath = path + agent.code
        val dependencies = dependencyRepository.findAllBySourceVersionIdOrderByIdAsc(version.id)
        val edges = resolveEdges(
            dependencies = dependencies,
            index = 0,
            currentPath = currentPath,
            warnings = warnings,
            depth = depth,
            remainingSteps = stepBudget - 1,
            explorationBudget = explorationBudget,
            selectionSeed = selectionSeed,
            allowUnresolvedRequired = allowUnresolvedRequired,
            allowPriceExceeded = allowPriceExceeded,
        )
        return ResolvedNode(
            version = toResolvedVersion(
                version = version,
                code = agent.code,
                name = agent.name,
                description = agent.description,
            ),
            dependencies = edges,
        )
    }

    private fun resolveEdges(
        dependencies: List<AgentDependency>,
        index: Int,
        currentPath: List<String>,
        warnings: MutableList<QuoteWarning>,
        depth: Int,
        remainingSteps: Int,
        explorationBudget: ProviderExplorationBudget,
        selectionSeed: UUID,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean,
    ): List<ResolvedEdge> {
        if (index >= dependencies.size) {
            return emptyList()
        }
        val dependency = dependencies[index]
        validateConstraint(constraint = dependency.versionConstraint)
        if (dependency.functionContractId != null) {
            return resolveFunctionEdges(
                dependency = dependency,
                currentPath = currentPath,
                warnings = warnings,
                depth = depth,
                remainingSteps = remainingSteps,
                explorationBudget = explorationBudget,
                selectionSeed = selectionSeed,
                allowUnresolvedRequired = allowUnresolvedRequired,
                allowPriceExceeded = allowPriceExceeded,
                resolveTail = { nextRemaining, branchWarnings ->
                    resolveEdges(
                        dependencies = dependencies,
                        index = index + 1,
                        currentPath = currentPath,
                        warnings = branchWarnings,
                        depth = depth,
                        remainingSteps = nextRemaining,
                        explorationBudget = explorationBudget,
                        selectionSeed = selectionSeed,
                        allowUnresolvedRequired = allowUnresolvedRequired,
                        allowPriceExceeded = allowPriceExceeded,
                    )
                },
            )
        }
        val edge = resolveDirectEdge(
            dependency = dependency,
            currentPath = currentPath,
            warnings = warnings,
            depth = depth,
            stepBudget = remainingSteps / dependency.maxCalls,
            explorationBudget = explorationBudget,
            selectionSeed = selectionSeed,
            allowUnresolvedRequired = allowUnresolvedRequired,
            allowPriceExceeded = allowPriceExceeded,
        )
        val consumedSteps = edge.resolved?.let { child ->
            dependency.maxCalls * expandedStepCount(node = child)
        } ?: 0
        if (consumedSteps > remainingSteps) {
            throw DomainClientException(ErrorCode.EXECUTION_STEPS_EXCEEDED)
        }
        return listOf(edge) + resolveEdges(
            dependencies = dependencies,
            index = index + 1,
            currentPath = currentPath,
            warnings = warnings,
            depth = depth,
            remainingSteps = remainingSteps - consumedSteps,
            explorationBudget = explorationBudget,
            selectionSeed = selectionSeed,
            allowUnresolvedRequired = allowUnresolvedRequired,
            allowPriceExceeded = allowPriceExceeded,
        )
    }

    private fun resolveFunctionEdges(
        dependency: AgentDependency,
        currentPath: List<String>,
        warnings: MutableList<QuoteWarning>,
        depth: Int,
        remainingSteps: Int,
        explorationBudget: ProviderExplorationBudget,
        selectionSeed: UUID,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean,
        resolveTail: (Int, MutableList<QuoteWarning>) -> List<ResolvedEdge>,
    ): List<ResolvedEdge> {
        val functionContractId = dependency.functionContractId
            ?: throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
        val providerScope = dependency.providerScope
            ?: throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
        val contract = capabilityService.requireCapability(id = functionContractId)
        val active = scopedCandidates(dependency = dependency, functionContractId = functionContractId)
        if (active.size > MAX_PROVIDER_CANDIDATES) {
            throw DomainClientException(ErrorCode.PROVIDER_CANDIDATE_LIMIT_EXCEEDED)
        }
        val metrics = providerMetricService.performance(
            functionContractId = functionContractId,
            versionIds = active.map { version -> version.id },
        )
        val strategy = dependency.selectionStrategy
        val explorationSelected = shouldExplore(
            dependency = dependency,
            strategy = strategy,
            candidates = active,
            metrics = metrics,
            selectionSeed = selectionSeed,
        )
        val sorted = orderFunctionCandidates(
            candidates = active,
            dependency = dependency,
            strategy = strategy,
            metrics = metrics,
            explorationSelected = explorationSelected,
            selectionSeed = selectionSeed,
        )
        val summaries = mutableListOf<ProviderCandidate>()
        val stepBudget = remainingSteps / dependency.maxCalls

        for (candidate in sorted) {
            explorationBudget.consume()
            val candidateAgent = agentService.requireAgent(candidate.agentId)
            val performance = metrics[candidate.id]
            val rejection = staticRejection(
                candidate = candidate,
                candidateCode = candidateAgent.code,
                dependency = dependency,
                currentPath = currentPath,
                allowPriceExceeded = allowPriceExceeded,
            ) ?: metricRejection(dependency = dependency, performance = performance)
            if (rejection != null) {
                summaries += summary(
                    version = candidate,
                    code = candidateAgent.code,
                    status = rejection,
                    performance = performance,
                )
                continue
            }
            val candidateWarnings = mutableListOf<QuoteWarning>()
            try {
                val selectedNode = resolveNode(
                    version = candidate,
                    path = currentPath,
                    warnings = candidateWarnings,
                    depth = depth + 1,
                    stepBudget = stepBudget,
                    explorationBudget = explorationBudget,
                    selectionSeed = selectionSeed,
                    allowUnresolvedRequired = allowUnresolvedRequired,
                    allowPriceExceeded = allowPriceExceeded,
                )
                if (expandedStepCount(node = selectedNode) > stepBudget) {
                    summaries += summary(
                        version = candidate,
                        code = candidateAgent.code,
                        status = "step_limit_exceeded",
                        performance = performance,
                    )
                    continue
                }
                val consumedSteps = dependency.maxCalls * expandedStepCount(node = selectedNode)
                val tail = resolveTail(remainingSteps - consumedSteps, candidateWarnings)
                summaries += summary(
                    version = candidate,
                    code = candidateAgent.code,
                    status = "selected",
                    performance = performance,
                )
                sorted.drop(summaries.size).forEach { remaining ->
                    val remainingAgent = agentService.requireAgent(remaining.agentId)
                    summaries += summary(
                        version = remaining,
                        code = remainingAgent.code,
                        status = "not_selected",
                        performance = metrics[remaining.id],
                    )
                }
                warnings += candidateWarnings
                return listOf(
                    ResolvedEdge(
                        dependency = dependency,
                        targetAgentCode = candidateAgent.code,
                        resolved = selectedNode,
                        selection = ProviderSelection(
                            strategy = strategy,
                            providerScope = providerScope,
                            functionContractId = contract.id,
                            functionCode = contract.key,
                            functionContractVersion = contract.contractVersion,
                            candidates = summaries,
                            selectedVersionId = candidate.id,
                            selectedReason = if (explorationSelected) {
                                "selected_by_exploration"
                            } else {
                                "selected_by_${strategy?.value ?: "pinned"}"
                            },
                            explorationSelected = explorationSelected,
                            selectionSeedDigest = selectionSeedDigest(
                                dependency = dependency,
                                selectionSeed = selectionSeed,
                            ),
                        ),
                    ),
                ) + tail
            } catch (exception: ClientException) {
                if (exception.errorCode !in SKIPPABLE_CANDIDATE_ERRORS) {
                    throw exception
                }
                summaries += summary(
                    version = candidate,
                    code = candidateAgent.code,
                    status = candidateFailureStatus(errorCode = exception.errorCode),
                    performance = performance,
                )
            }
        }
        if (dependency.isRequired && !allowUnresolvedRequired) {
            throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
        }
        warnings += QuoteWarning(
            code = "OPTIONAL_DEPENDENCY_NOT_RESOLVED",
            dependencyId = dependency.id,
            functionContractId = contract.id,
            functionCode = contract.key,
            versionConstraint = dependency.versionConstraint,
        )
        return listOf(
            ResolvedEdge(
                dependency = dependency,
                targetAgentCode = null,
                resolved = null,
                selection = ProviderSelection(
                    strategy = strategy,
                    providerScope = providerScope,
                    functionContractId = contract.id,
                    functionCode = contract.key,
                    functionContractVersion = contract.contractVersion,
                    candidates = summaries,
                    selectedVersionId = null,
                    selectedReason = null,
                    explorationSelected = explorationSelected,
                    selectionSeedDigest = selectionSeedDigest(
                        dependency = dependency,
                        selectionSeed = selectionSeed,
                    ),
                ),
            ),
        ) + resolveTail(remainingSteps, warnings)
    }

    private fun resolveDirectEdge(
        dependency: AgentDependency,
        currentPath: List<String>,
        warnings: MutableList<QuoteWarning>,
        depth: Int,
        stepBudget: Int,
        explorationBudget: ProviderExplorationBudget,
        selectionSeed: UUID,
        allowUnresolvedRequired: Boolean,
        allowPriceExceeded: Boolean,
    ): ResolvedEdge {
        val targetAgentId = dependency.targetAgentId
            ?: throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
        val target = agentService.requireAgent(targetAgentId)
        val selected = newest(
            versions = agentService.activeVersions(targetAgentId)
                .filter { candidate -> matches(version = candidate.semver, constraint = dependency.versionConstraint) },
        )
        if (selected == null) {
            return unresolvedEdge(
                dependency = dependency,
                targetAgentCode = target.code,
                warnings = warnings,
                allowUnresolvedRequired = allowUnresolvedRequired,
            )
        }
        requireNoCycle(targetCode = target.code, currentPath = currentPath)
        requirePrice(
            selected = selected,
            dependency = dependency,
            allowPriceExceeded = allowPriceExceeded,
        )
        return ResolvedEdge(
            dependency = dependency,
            targetAgentCode = target.code,
            resolved = resolveNode(
                version = selected,
                path = currentPath,
                warnings = warnings,
                depth = depth + 1,
                stepBudget = stepBudget,
                explorationBudget = explorationBudget,
                selectionSeed = selectionSeed,
                allowUnresolvedRequired = allowUnresolvedRequired,
                allowPriceExceeded = allowPriceExceeded,
            ),
        )
    }

    private fun unresolvedEdge(
        dependency: AgentDependency,
        targetAgentCode: String,
        warnings: MutableList<QuoteWarning>,
        allowUnresolvedRequired: Boolean,
    ): ResolvedEdge {
        if (dependency.isRequired && !allowUnresolvedRequired) {
            throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
        }
        warnings += QuoteWarning(
            code = "OPTIONAL_DEPENDENCY_NOT_RESOLVED",
            dependencyId = dependency.id,
            targetAgentId = dependency.targetAgentId,
            targetAgentCode = targetAgentCode,
            versionConstraint = dependency.versionConstraint,
        )
        return ResolvedEdge(
            dependency = dependency,
            targetAgentCode = targetAgentCode,
            resolved = null,
        )
    }

    private fun staticRejection(
        candidate: AgentVersion,
        candidateCode: String,
        dependency: AgentDependency,
        currentPath: List<String>,
        allowPriceExceeded: Boolean,
    ): String? {
        if (!matches(version = candidate.semver, constraint = dependency.versionConstraint)) {
            return "version_mismatch"
        }
        if (!allowPriceExceeded && candidate.priceAtomic > dependency.maxPriceAtomic) {
            return "price_exceeded"
        }
        if (candidateCode in currentPath) {
            return "cycle"
        }
        return null
    }

    private fun scopedCandidates(
        dependency: AgentDependency,
        functionContractId: UUID,
    ): List<AgentVersion> {
        val all = agentService.activeVersionsForCapability(capabilityId = functionContractId)
        return when (dependency.providerScope) {
            ProviderScope.PINNED -> {
                val targetAgentId = dependency.targetAgentId
                    ?: throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
                all.filter { candidate -> candidate.agentId == targetAgentId }
            }
            ProviderScope.ALLOWLIST -> {
                val allowedAgentIds = allowedProviderRepository
                    .findAllByIdDependencyId(dependency.id)
                    .map { provider -> provider.id.agentId }
                    .toSet()
                all.filter { candidate -> candidate.agentId in allowedAgentIds }
            }
            ProviderScope.MARKETPLACE -> all
            null -> throw DomainClientException(ErrorCode.DEPENDENCY_NOT_RESOLVED)
        }
    }

    private fun metricRejection(
        dependency: AgentDependency,
        performance: ProviderPerformanceDto?,
    ): String? {
        val reliability = dependency.minReliabilityPercent
        if (reliability != null && (performance?.reliabilityPercent ?: -1) < reliability) {
            return "reliability_below_minimum"
        }
        val maxP95 = dependency.maxP95LatencyMillis
        if (maxP95 != null && (performance?.p95LatencyMillis ?: Long.MAX_VALUE) > maxP95) {
            return "latency_above_maximum"
        }
        return null
    }

    private fun shouldExplore(
        dependency: AgentDependency,
        strategy: ProviderSelectionStrategy?,
        candidates: List<AgentVersion>,
        metrics: Map<UUID, ProviderPerformanceDto>,
        selectionSeed: UUID,
    ): Boolean {
        if (!isMetricStrategy(strategy = strategy)) {
            return false
        }
        val lowSample = candidates.any { candidate -> metrics[candidate.id]?.isMature != true }
        if (!lowSample) {
            return false
        }
        val explorationPercent = dependency.explorationPercent ?: 0
        if (explorationPercent == 0) {
            return false
        }
        val hasMature = candidates.any { candidate -> metrics[candidate.id]?.isMature == true }
        if (!hasMature) {
            return true
        }
        val bucket = explorationDigest(
            dependency = dependency,
            selectionSeed = selectionSeed,
            version = null,
        ).take(2).toInt(radix = 16) * 100 / 256
        return bucket < explorationPercent
    }

    private fun orderFunctionCandidates(
        candidates: List<AgentVersion>,
        dependency: AgentDependency,
        strategy: ProviderSelectionStrategy?,
        metrics: Map<UUID, ProviderPerformanceDto>,
        explorationSelected: Boolean,
        selectionSeed: UUID,
    ): List<AgentVersion> {
        if (dependency.providerScope == ProviderScope.PINNED) {
            return candidates.sortedWith(::compareByVersionThenPrice)
        }
        if (!isMetricStrategy(strategy = strategy)) {
            return candidates.sortedWith(
                compareByStrategy(
                    strategy = strategy ?: ProviderSelectionStrategy.LOWEST_PRICE,
                    metrics = metrics,
                    weights = weights(dependency = dependency),
                    candidates = candidates,
                ),
            )
        }
        val mature = candidates.filter { candidate -> metrics[candidate.id]?.isMature == true }
        val lowSample = candidates.filter { candidate -> metrics[candidate.id]?.isMature != true }
        if (!explorationSelected && mature.isEmpty()) {
            throw DomainClientException(ErrorCode.PROVIDER_METRICS_INSUFFICIENT)
        }
        val metricOrder = compareByStrategy(
            strategy = strategy ?: ProviderSelectionStrategy.HIGHEST_RELIABILITY,
            metrics = metrics,
            weights = weights(dependency = dependency),
            candidates = mature,
        )
        val orderedMature = mature.sortedWith(metricOrder)
        if (!explorationSelected) {
            return orderedMature
        }
        val exploratory = lowSample.sortedWith { left, right ->
            val leftDigest = explorationDigest(
                dependency = dependency,
                selectionSeed = selectionSeed,
                version = left,
            )
            val rightDigest = explorationDigest(
                dependency = dependency,
                selectionSeed = selectionSeed,
                version = right,
            )
            val digestOrder = leftDigest.compareTo(rightDigest)
            if (digestOrder != 0) digestOrder else compareByVersionThenPrice(left, right)
        }
        return exploratory + orderedMature
    }

    private fun isMetricStrategy(strategy: ProviderSelectionStrategy?): Boolean {
        return strategy == ProviderSelectionStrategy.HIGHEST_RELIABILITY ||
            strategy == ProviderSelectionStrategy.FASTEST || strategy == ProviderSelectionStrategy.BALANCED
    }

    private fun compareByStrategy(
        strategy: ProviderSelectionStrategy,
        metrics: Map<UUID, ProviderPerformanceDto>,
        weights: SelectionWeights,
        candidates: List<AgentVersion>,
    ): Comparator<AgentVersion> {
        return Comparator { first, second ->
            val result = when (strategy) {
                ProviderSelectionStrategy.LOWEST_PRICE -> first.priceAtomic.compareTo(second.priceAtomic)
                ProviderSelectionStrategy.LATEST_VERSION -> -compareSemver(first.semver, second.semver)
                ProviderSelectionStrategy.HIGHEST_RELIABILITY -> {
                    -(metrics[first.id]?.reliabilityPercent ?: -1)
                        .compareTo(metrics[second.id]?.reliabilityPercent ?: -1)
                }
                ProviderSelectionStrategy.FASTEST -> {
                    (metrics[first.id]?.p95LatencyMillis ?: Long.MAX_VALUE)
                        .compareTo(metrics[second.id]?.p95LatencyMillis ?: Long.MAX_VALUE)
                }
                ProviderSelectionStrategy.BALANCED -> -balancedScore(
                    candidate = first,
                    candidates = candidates,
                    metrics = metrics,
                    weights = weights,
                ).compareTo(
                    balancedScore(
                        candidate = second,
                        candidates = candidates,
                        metrics = metrics,
                        weights = weights,
                    ),
                )
            }
            if (result != 0) {
                result
            } else {
                compareByVersionThenPrice(first, second)
            }
        }
    }

    private fun compareByVersionThenPrice(left: AgentVersion, right: AgentVersion): Int {
        val versionOrder = -compareSemver(left.semver, right.semver)
        if (versionOrder != 0) {
            return versionOrder
        }
        val priceOrder = left.priceAtomic.compareTo(right.priceAtomic)
        if (priceOrder != 0) {
            return priceOrder
        }
        val agentOrder = left.agentId.compareTo(right.agentId)
        return if (agentOrder != 0) agentOrder else left.id.compareTo(right.id)
    }

    private fun balancedScore(
        candidate: AgentVersion,
        candidates: List<AgentVersion>,
        metrics: Map<UUID, ProviderPerformanceDto>,
        weights: SelectionWeights,
    ): Double {
        val reliability = (metrics[candidate.id]?.reliabilityPercent ?: 0) / 100.0
        val priceScore = inverseBigIntegerScore(
            values = candidates.map(AgentVersion::getPriceAtomic),
            value = candidate.priceAtomic,
        )
        val speedScore = inverseLongScore(
            values = candidates.mapNotNull { version -> metrics[version.id]?.p95LatencyMillis },
            value = metrics[candidate.id]?.p95LatencyMillis ?: Long.MAX_VALUE,
        )
        return reliability * weights.reliability / 100.0 + priceScore * weights.price / 100.0 +
            speedScore * weights.speed / 100.0
    }

    private fun inverseBigIntegerScore(values: List<BigInteger>, value: BigInteger): Double {
        val minimum = values.minOrNull() ?: return 0.0
        val maximum = values.maxOrNull() ?: return 0.0
        if (minimum == maximum) {
            return 1.0
        }
        val numerator = value.subtract(minimum).toDouble()
        val denominator = maximum.subtract(minimum).toDouble()
        return 1.0 - numerator / denominator
    }

    private fun inverseLongScore(values: List<Long>, value: Long): Double {
        val minimum = values.minOrNull() ?: return 0.0
        val maximum = values.maxOrNull() ?: return 0.0
        if (minimum == maximum) {
            return 1.0
        }
        return 1.0 - (value - minimum).toDouble() / (maximum - minimum).toDouble()
    }

    private fun weights(dependency: AgentDependency): SelectionWeights {
        return SelectionWeights(
            reliability = dependency.reliabilityWeight ?: 0,
            price = dependency.priceWeight ?: 0,
            speed = dependency.speedWeight ?: 0,
        )
    }

    private fun selectionSeedDigest(dependency: AgentDependency, selectionSeed: UUID): String {
        return explorationDigest(
            dependency = dependency,
            selectionSeed = selectionSeed,
            version = null,
        )
    }

    private fun explorationDigest(
        dependency: AgentDependency,
        selectionSeed: UUID,
        version: AgentVersion?,
    ): String {
        val source = buildString {
            append(selectionSeed)
            append(':')
            append(dependency.id)
            version?.let { value -> append(':').append(value.id) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class SelectionWeights(
        val reliability: Int,
        val price: Int,
        val speed: Int,
    )

    private fun requireNoCycle(targetCode: String, currentPath: List<String>) {
        if (targetCode in currentPath) {
            val cycle = currentPath.dropWhile { code -> code != targetCode } + targetCode
            throw DependencyCycleDetectedException(cycle.joinToString(" -> "))
        }
    }

    private fun requirePrice(
        selected: AgentVersion,
        dependency: AgentDependency,
        allowPriceExceeded: Boolean,
    ) {
        if (!allowPriceExceeded && selected.priceAtomic > dependency.maxPriceAtomic) {
            throw DomainClientException(ErrorCode.DEPENDENCY_PRICE_EXCEEDED)
        }
    }

    private fun compareSemver(left: String, right: String): Int {
        return Semver(left).compareTo(Semver(right))
    }

    private fun expandedStepCount(node: ResolvedNode): Int {
        var steps = 1L
        node.dependencies.forEach { edge ->
            val child = edge.resolved ?: return@forEach
            steps += edge.dependency.maxCalls.toLong() * expandedStepCount(node = child)
            if (steps > 32) {
                return 33
            }
        }
        return steps.toInt()
    }

    private fun candidateFailureStatus(errorCode: ErrorCode): String {
        return when (errorCode) {
            ErrorCode.DEPENDENCY_DEPTH_EXCEEDED -> "depth_limit_exceeded"
            ErrorCode.EXECUTION_STEPS_EXCEEDED -> "step_limit_exceeded"
            else -> "unresolved_graph"
        }
    }

    private fun summary(
        version: AgentVersion,
        code: String,
        status: String,
        performance: ProviderPerformanceDto? = null,
    ): ProviderCandidate {
        return ProviderCandidate(
            agentId = version.agentId,
            agentCode = code,
            versionId = version.id,
            semver = version.semver,
            priceAtomic = version.priceAtomic,
            status = status,
            observationCount = performance?.observationCount,
            reliabilityPercent = performance?.reliabilityPercent,
            p95LatencyMillis = performance?.p95LatencyMillis,
            contractCompliancePercent = performance?.contractCompliancePercent,
        )
    }

    private fun toResolvedVersion(
        version: AgentVersion,
        code: String,
        name: String,
        description: String,
    ): ResolvedVersion {
        val functionContract = version.capabilityId?.let { capabilityId ->
            val contract = capabilityService.requireCapability(id = capabilityId)
            ResolvedFunctionContract(
                id = contract.id,
                key = contract.key,
                contractVersion = contract.contractVersion,
                inputSchema = contract.inputSchema,
                outputSchema = contract.outputSchema,
            )
        }
        return ResolvedVersion(
            id = version.id,
            agentId = version.agentId,
            agentCode = code,
            agentName = name,
            agentDescription = description,
            semver = version.semver,
            endpoint = version.endpoint,
            priceAtomic = version.priceAtomic,
            network = version.network,
            asset = version.asset,
            payTo = version.payTo,
            responseFormat = version.responseFormat,
            functionContract = functionContract,
        )
    }

    private class ProviderExplorationBudget(private var remaining: Int) {
        fun consume() {
            if (remaining == 0) {
                throw DomainClientException(ErrorCode.PROVIDER_EXPLORATION_LIMIT_EXCEEDED)
            }
            remaining -= 1
        }
    }
}
