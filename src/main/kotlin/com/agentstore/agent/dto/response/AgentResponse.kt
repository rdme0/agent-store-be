package com.agentstore.agent.dto.response

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.model.vo.AgentVersionStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

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
    @field:Schema(allowableValues = ["TEXT", "MARKDOWN", "STRUCTURED", "JSON"])
    val responseFormat: AgentResponseFormat,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(version: AgentVersion): AgentVersionResponse {
            return AgentVersionResponse(
                id = version.id,
                agentId = version.agentId,
                semver = version.semver,
                status = version.status,
                endpoint = version.endpoint,
                priceAtomic = version.priceAtomic.toString(),
                network = version.network,
                asset = version.asset,
                payTo = version.payTo,
                responseFormat = version.responseFormat,
                createdAt = version.createdAt,
                updatedAt = version.updatedAt,
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
    @field:Schema(minimum = "0")
    val dependencyCount: Int,
    val versions: List<AgentVersionResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(
            agent: Agent,
            developerName: String,
            dependencyCount: Int,
            versions: List<AgentVersion>
        ): AgentResponse {
            return AgentResponse(
                id = agent.id,
                developerId = agent.developerId,
                developerName = developerName,
                slug = agent.slug,
                name = agent.name,
                description = agent.description,
                dependencyCount = dependencyCount,
                versions = versions.sortedBy { version -> version.createdAt }.map(AgentVersionResponse::from),
                createdAt = agent.createdAt,
                updatedAt = agent.updatedAt,
            )
        }
    }
}

data class AgentListResponse(
    val items: List<AgentResponse>,
    @field:Schema(
        nullable = false,
        description = "서명된 opaque cursor"
    ) val nextCursor: String? = null,
)
