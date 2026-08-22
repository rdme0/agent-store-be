package com.agentstore.dependency.model.vo

import com.agentstore.dependency.dto.internal.DependencySnapshotDto
import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.dependency.dto.internal.ResolvedVersionSnapshotDto

data class ResolvedNode(val version: ResolvedVersion, val dependencies: List<ResolvedEdge>) {
    fun snapshot(): QuoteSnapshotDto {
        return QuoteSnapshotDto(
            version = ResolvedVersionSnapshotDto(
                id = version.id,
                agentId = version.agentId,
                agentSlug = version.agentSlug,
                semver = version.semver,
                endpoint = version.endpoint,
                priceAtomic = version.priceAtomic.toString(),
                network = version.network,
                asset = version.asset,
                payTo = version.payTo,
                responseFormat = version.responseFormat,
            ),
            dependencies = dependencies.map { edge ->
                DependencySnapshotDto(
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
}
