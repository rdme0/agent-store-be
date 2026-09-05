package com.agentstore.agent.dto.response

import com.agentstore.agent.model.entity.AgentVersionReadiness
import com.agentstore.agent.model.vo.AgentVersionReadinessStatus
import java.time.Instant
import java.util.UUID

data class AgentVersionReadinessResponse(
    val versionId: UUID,
    val status: AgentVersionReadinessStatus,
    val lastPaidCertificationAt: Instant? = null,
    val lastPreflightAt: Instant? = null,
    val certificationTransactionHash: String? = null,
    val failureCode: String? = null,
) {
    companion object {
        fun from(readiness: AgentVersionReadiness): AgentVersionReadinessResponse {
            return AgentVersionReadinessResponse(
                versionId = readiness.versionId,
                status = readiness.status,
                lastPaidCertificationAt = readiness.lastPaidCertificationAt,
                lastPreflightAt = readiness.lastPreflightAt,
                certificationTransactionHash = readiness.certificationTransactionHash,
                failureCode = readiness.failureCode,
            )
        }
    }
}
