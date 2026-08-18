package com.agentstore.dependency.service

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.dependency.dto.DependencySnapshot
import com.agentstore.dependency.dto.QuoteSnapshot
import com.agentstore.dependency.dto.ResolvedVersionSnapshot

data class ResolvedNode(val version: AgentVersion, val dependencies: List<ResolvedEdge>) {
    fun snapshot(): QuoteSnapshot = QuoteSnapshot(
        version = ResolvedVersionSnapshot(
            id = version.id,
            agentId = version.agent.id,
            agentSlug = version.agent.slug,
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
                targetAgentId = edge.dependency.targetAgent.id,
                targetAgentSlug = edge.dependency.targetAgent.slug,
                versionConstraint = edge.dependency.versionConstraint,
                required = edge.dependency.isRequired,
                maxPriceAtomic = edge.dependency.maxPriceAtomic.toString(),
                maxCalls = edge.dependency.maxCalls,
                resolved = edge.resolved?.snapshot(),
            )
        },
    )
}
