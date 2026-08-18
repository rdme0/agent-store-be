package com.agentstore.dependency.model.vo

import com.agentstore.dependency.dto.internal.DependencySnapshot
import com.agentstore.dependency.dto.internal.QuoteSnapshot
import com.agentstore.dependency.dto.internal.ResolvedVersionSnapshot

data class ResolvedNode(val version: ResolvedVersion, val dependencies: List<ResolvedEdge>) {
    fun snapshot(): QuoteSnapshot = QuoteSnapshot(
        version = ResolvedVersionSnapshot(
            id = version.id,
            agentId = version.agentId,
            agentSlug = version.agentSlug,
            semver = version.semver,
            endpoint = version.endpoint,
            priceAtomic = version.priceAtomic.toString(),
            network = version.network,
            asset = version.asset,
            payTo = version.payTo,
        ),
        dependencies = dependencies.map { edge ->
            DependencySnapshot(
                dependencyId = edge.dependency.id,
                targetAgentId = edge.dependency.targetAgentId,
                targetAgentSlug = edge.targetAgentSlug,
                versionConstraint = edge.dependency.versionConstraint,
                required = edge.dependency.isRequired,
                maxPriceAtomic = edge.dependency.maxPriceAtomic.toString(),
                maxCalls = edge.dependency.maxCalls,
                resolved = edge.resolved?.snapshot(),
            )
        },
    )
}
