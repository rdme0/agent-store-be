package com.agentstore.agent.dto

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

data class CreateAgentRequest(
    val developerId: UUID,
    @field:NotBlank @field:Size(max = 80)
    @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
    val slug: String,
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank @field:Size(max = 2000) val description: String,
    @field:NotBlank @field:Size(max = 32) val semver: String,
    @field:NotBlank @field:Size(max = 2048) val endpoint: String,
    @field:Pattern(regexp = "^[0-9]+$") val priceAtomic: String,
    @field:NotBlank @field:Size(max = 128) val network: String,
    @field:NotBlank @field:Size(max = 128) val asset: String,
    @field:NotBlank @field:Size(max = 128) val payTo: String,
)

data class UpdateAgentRequest(
    @field:Size(min = 1, max = 120) val name: String? = null,
    @field:Size(min = 1, max = 2000) val description: String? = null,
) {
    fun isEmpty() = name == null && description == null
}

data class CreateAgentVersionRequest(
    @field:NotBlank @field:Size(max = 32) val semver: String,
    @field:NotBlank @field:Size(max = 2048) val endpoint: String,
    @field:Pattern(regexp = "^[0-9]+$") val priceAtomic: String,
    @field:NotBlank @field:Size(max = 128) val network: String,
    @field:NotBlank @field:Size(max = 128) val asset: String,
    @field:NotBlank @field:Size(max = 128) val payTo: String,
)

data class AgentVersionResponse(
    val id: UUID,
    val agentId: UUID,
    val semver: String,
    val status: AgentVersionStatus,
    val endpoint: String,
    val priceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(version: AgentVersion) = AgentVersionResponse(
            id = version.id,
            agentId = version.agent.id,
            semver = version.semver,
            status = version.status,
            endpoint = version.endpoint,
            priceAtomic = version.priceAtomic.toString(),
            network = version.network,
            asset = version.asset,
            payTo = version.payTo,
            createdAt = version.createdAt,
            updatedAt = version.updatedAt,
        )
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
        fun from(agent: Agent) = AgentResponse(
            id = agent.id,
            developerId = agent.developer.id,
            developerName = agent.developer.displayName,
            slug = agent.slug,
            name = agent.name,
            description = agent.description,
            versions = agent.versions.sortedBy { it.createdAt }.map(AgentVersionResponse::from),
            createdAt = agent.createdAt,
            updatedAt = agent.updatedAt,
        )
    }
}

data class AgentListResponse(
    val items: List<AgentResponse>,
    val nextCursor: UUID? = null,
)
