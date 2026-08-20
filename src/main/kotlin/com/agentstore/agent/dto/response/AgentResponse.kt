package com.agentstore.agent.dto.response

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.*

data class AgentVersionResponse(
    val id: UUID,
    val agentId: UUID,
    val semver: String,
    @field:Schema(allowableValues = ["DRAFT", "ACTIVE", "DISABLED"])
    val status: AgentVersionStatus,
    @field:Schema(format = "uri")
    val endpoint: String,
    @field:Schema(pattern = "^[0-9]+$")
    val priceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(version: AgentVersion): AgentVersionResponse {
            return AgentVersionResponse(
                version.id,
                version.agentId,
                version.semver,
                version.status,
                version.endpoint,
                version.priceAtomic.toString(),
                version.network,
                version.asset,
                version.payTo,
                version.createdAt,
                version.updatedAt
            )
        }
    }
}

data class AgentResponse(
    val id: UUID,
    val developerId: UUID,
    val developerName: String,
    val slug: String,
    val name: String,
    val description: String,
    val versions: List<AgentVersionResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(agent: Agent, developerName: String, versions: List<AgentVersion>): AgentResponse {
            return AgentResponse(
                agent.id,
                agent.developerId,
                developerName,
                agent.slug,
                agent.name,
                agent.description,
                versions.sortedBy { it.createdAt }.map(AgentVersionResponse::from),
                agent.createdAt,
                agent.updatedAt
            )
        }
    }
}

data class AgentListResponse(
    val items: List<AgentResponse>,
    @field:Schema(nullable = false, format = "uuid") val nextCursor: UUID? = null,
)
