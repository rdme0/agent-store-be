package com.agentstore.agent.service

import com.agentstore.agent.dto.AgentListResponse
import com.agentstore.agent.dto.AgentResponse
import com.agentstore.agent.dto.AgentVersionResponse
import com.agentstore.agent.dto.CreateAgentRequest
import com.agentstore.agent.dto.CreateAgentVersionRequest
import com.agentstore.agent.dto.UpdateAgentRequest
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.common.web.ApiException
import jakarta.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.net.URI
import java.util.UUID

@Service
class AgentService(
    private val agentRepository: AgentRepository,
    private val agentVersionRepository: AgentVersionRepository,
    private val developerRepository: DeveloperRepository,
) {
    @Transactional
    fun list(limit: Int, cursor: UUID?): AgentListResponse {
        requireLimit(limit)
        val active = agentRepository.findAllByOrderByCreatedAtDesc()
            .filter { agent -> agent.versions.any { it.status == AgentVersionStatus.ACTIVE } }
            .filter { cursor == null || it.id.toString() < cursor.toString() }
        val page = active.take(limit + 1)
        return AgentListResponse(
            items = page.take(limit).map(AgentResponse::from),
            nextCursor = page.getOrNull(limit)?.id,
        )
    }

    @Transactional
    fun getBySlug(slug: String): AgentResponse = agentRepository.findBySlug(slug)?.let(AgentResponse::from)
        ?: throw ApiException("AGENT_NOT_FOUND", "Agent was not found", 404, mapOf("slug" to slug))

    @Transactional
    fun create(request: CreateAgentRequest): AgentResponse {
        validateVersion(request.semver, request.endpoint, request.priceAtomic, request.network, request.asset, request.payTo)
        val developer = developerRepository.findById(request.developerId).orElseThrow {
            ApiException("DEVELOPER_NOT_FOUND", "Developer was not found", 404, mapOf("id" to request.developerId))
        }
        val agent = Agent(UUID.randomUUID(), developer, request.slug, request.name, request.description)
        val version = AgentVersion(UUID.randomUUID(), agent, request.semver, request.endpoint, BigInteger(request.priceAtomic), request.network, request.asset, request.payTo)
        agent.versions.add(version)
        return try {
            AgentResponse.from(agentRepository.save(agent))
        } catch (exception: DataIntegrityViolationException) {
            throw ApiException("AGENT_ALREADY_EXISTS", "Agent slug or version already exists", 409)
        }
    }

    @Transactional
    fun update(id: UUID, request: UpdateAgentRequest): AgentResponse {
        if (request.isEmpty()) throw ApiException("VALIDATION_ERROR", "At least one field is required", 422)
        val agent = agentRepository.findById(id).orElseThrow { ApiException("AGENT_NOT_FOUND", "Agent was not found", 404) }
        agent.updateMetadata(request.name ?: agent.name, request.description ?: agent.description)
        return AgentResponse.from(agent)
    }

    @Transactional
    fun createVersion(agentId: UUID, request: CreateAgentVersionRequest): AgentVersionResponse {
        validateVersion(request.semver, request.endpoint, request.priceAtomic, request.network, request.asset, request.payTo)
        val agent = agentRepository.findById(agentId).orElseThrow { ApiException("AGENT_NOT_FOUND", "Agent was not found", 404) }
        if (agentVersionRepository.findByAgentIdAndSemver(agentId, request.semver) != null) {
            throw ApiException("AGENT_VERSION_ALREADY_EXISTS", "Agent version already exists", 409)
        }
        val version = agentVersionRepository.save(AgentVersion(UUID.randomUUID(), agent, request.semver, request.endpoint, BigInteger(request.priceAtomic), request.network, request.asset, request.payTo))
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun publish(versionId: UUID): AgentVersionResponse {
        val version = agentVersionRepository.findWithAgentById(versionId)
            ?: throw ApiException("AGENT_VERSION_NOT_FOUND", "Agent version was not found", 404)
        if (version.status != AgentVersionStatus.DRAFT) throw ApiException("INVALID_VERSION_TRANSITION", "Only DRAFT versions can be published", 409, mapOf("status" to version.status))
        version.publish()
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun disable(versionId: UUID): AgentVersionResponse {
        val version = agentVersionRepository.findWithAgentById(versionId)
            ?: throw ApiException("AGENT_VERSION_NOT_FOUND", "Agent version was not found", 404)
        if (version.status != AgentVersionStatus.ACTIVE) throw ApiException("INVALID_VERSION_TRANSITION", "Only ACTIVE versions can be disabled", 409, mapOf("status" to version.status))
        version.disable()
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun delete(id: UUID) {
        val agent = agentRepository.findById(id).orElseThrow { ApiException("AGENT_NOT_FOUND", "Agent was not found", 404) }
        if (agent.versions.isNotEmpty()) {
            throw ApiException("AGENT_HAS_VERSIONS", "An Agent with versions cannot be deleted; disable its ACTIVE versions instead", 409, mapOf("versionCount" to agent.versions.size))
        }
        agentRepository.delete(agent)
    }

    private fun validateVersion(semver: String, endpoint: String, priceAtomic: String, network: String, asset: String, payTo: String) {
        if (!SEMVER.matches(semver)) throw ApiException("INVALID_SEMVER", "semver must be a valid semantic version", 400)
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw ApiException("INVALID_ENDPOINT", "endpoint must be an absolute HTTP(S) URL", 400)
        if (priceAtomic.toBigIntegerOrNull() == null || BigInteger(priceAtomic) < BigInteger.ZERO) throw ApiException("INVALID_PRICE", "priceAtomic must be a non-negative atomic integer", 400)
        if (network.isBlank() || asset.isBlank() || payTo.isBlank()) throw ApiException("INVALID_PAYMENT_TERMS", "network, asset and payTo are required", 400)
    }

    private fun requireLimit(limit: Int) {
        if (limit !in 1..50) throw ApiException("VALIDATION_ERROR", "limit must be between 1 and 50", 422)
    }

    companion object {
        private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    }
}
