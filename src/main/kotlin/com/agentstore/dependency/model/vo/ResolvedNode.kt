package com.agentstore.dependency.model.vo

import com.agentstore.dependency.dto.internal.DependencySnapshotDto
import com.agentstore.dependency.dto.internal.FunctionContractSnapshotDto
import com.agentstore.dependency.dto.internal.ProviderCandidateSnapshotDto
import com.agentstore.dependency.dto.internal.ProviderSelectionSnapshotDto
import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.dependency.dto.internal.ResolvedVersionSnapshotDto

data class ResolvedNode(val version: ResolvedVersion, val dependencies: List<ResolvedEdge>) {
    fun snapshot(): QuoteSnapshotDto {
        return QuoteSnapshotDto(
            version = ResolvedVersionSnapshotDto(
                id = version.id,
                agentId = version.agentId,
                agentCode = version.agentCode,
                agentName = version.agentName,
                agentDescription = version.agentDescription,
                semver = version.semver,
                endpoint = version.endpoint,
                priceAtomic = version.priceAtomic.toString(),
                network = version.network,
                asset = version.asset,
                payTo = version.payTo,
                responseFormat = version.responseFormat,
                functionContract = version.functionContract?.let { contract ->
                    FunctionContractSnapshotDto(
                        id = contract.id,
                        code = contract.code,
                        contractVersion = contract.contractVersion,
                        inputSchema = contract.inputSchema,
                        outputSchema = contract.outputSchema,
                    )
                },
            ),
            dependencies = dependencies.map { edge ->
                DependencySnapshotDto(
                    dependencyId = edge.dependency.id,
                    targetAgentId = edge.dependency.targetAgentId,
                    targetAgentCode = edge.targetAgentCode,
                    versionConstraint = edge.dependency.versionConstraint,
                    required = edge.dependency.isRequired,
                    maxPriceAtomic = edge.dependency.maxPriceAtomic.toString(),
                    maxCalls = edge.dependency.maxCalls,
                    selection = edge.selection?.let { selection ->
                        ProviderSelectionSnapshotDto(
                            strategy = selection.strategy,
                            providerScope = selection.providerScope,
                            functionContractId = selection.functionContractId,
                            functionCode = selection.functionCode,
                            functionContractVersion = selection.functionContractVersion,
                            candidates = selection.candidates.map { candidate ->
                                ProviderCandidateSnapshotDto(
                                    agentId = candidate.agentId,
                                    agentCode = candidate.agentCode,
                                    versionId = candidate.versionId,
                                    semver = candidate.semver,
                                    priceAtomic = candidate.priceAtomic.toString(),
                                    status = candidate.status,
                                    observationCount = candidate.observationCount,
                                    reliabilityPercent = candidate.reliabilityPercent,
                                    p95LatencyMillis = candidate.p95LatencyMillis,
                                    contractCompliancePercent = candidate.contractCompliancePercent,
                                )
                            },
                            selectedVersionId = selection.selectedVersionId,
                            selectedReason = selection.selectedReason,
                        )
                    },
                    resolved = edge.resolved?.snapshot(),
                )
            },
        )
    }
}
