package com.agentstore.agent.service

import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.dto.request.UpdateAgentRequest
import com.agentstore.agent.dto.response.AgentListResponse
import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.entity.Developer
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.common.exception.ApiException
import jakarta.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.*

@Service
class AgentService(
    private val agentRepository: AgentRepository,
    private val agentVersionRepository: AgentVersionRepository,
    private val developerRepository: DeveloperRepository,
    private val endpointPolicy: AgentEndpointPolicy,
) {
    /** Agent-owned read boundary used by dependency, quote and revenue use cases. */
    fun requireAgent(id: UUID): Agent {
        return agentRepository.findById(id).orElseThrow {
            ApiException("AGENT_NOT_FOUND", "Agent was not found", 404, mapOf("id" to id))
        }
    }

    fun findBySlug(slug: String): Agent? {
        return agentRepository.findBySlug(slug)
    }

    fun findByIdOrNull(id: UUID): Agent? {
        return agentRepository.findById(id).orElse(null)
    }

    fun requireVersion(id: UUID): AgentVersion {
        return agentVersionRepository.findById(id).orElseThrow {
            ApiException("AGENT_VERSION_NOT_FOUND", "Agent version was not found", 404, mapOf("id" to id))
        }
    }

    fun activeVersions(agentId: UUID): List<AgentVersion> {
        return agentVersionRepository.findAllByAgentIdAndStatus(agentId, AgentVersionStatus.ACTIVE)
    }

    fun draftOrActiveVersions(agentId: UUID): List<AgentVersion> {
        return activeVersions(agentId).ifEmpty {
            agentVersionRepository.findAllByAgentIdAndStatus(agentId, AgentVersionStatus.DRAFT)
        }
    }

    fun versions(agentId: UUID): List<AgentVersion> {
        return agentVersionRepository.findAllByAgentId(agentId)
    }

    fun versionBySemver(agentId: UUID, semver: String): AgentVersion? {
        return agentVersionRepository.findByAgentIdAndSemver(agentId, semver)
    }

    fun requireDeveloper(id: UUID): Developer {
        return developerRepository.findById(id).orElseThrow {
            ApiException("DEVELOPER_NOT_FOUND", "Developer was not found", 404, mapOf("id" to id))
        }
    }

    fun developerExists(id: UUID): Boolean {
        return developerRepository.existsById(id)
    }

    fun slugForAgent(id: UUID): String {
        return requireAgent(id).slug
    }

    fun developerIdForAgent(id: UUID): UUID {
        return requireAgent(id).developerId
    }

    fun developerIdForVersion(versionId: UUID): UUID {
        return developerIdForAgent(requireVersion(versionId).agentId)
    }

    @Transactional
    fun list(limit: Int, cursor: UUID?): AgentListResponse {
        requireLimit(limit)
        val active = agentRepository.findAllByOrderByCreatedAtDesc()
            .map { agent -> agent to activeVersions(agent.id) }
            .filter { (_, versions) -> versions.isNotEmpty() }
            .filter { cursor == null || it.first.id.toString() < cursor.toString() }
        val page = active.take(limit + 1)
        return AgentListResponse(
            items = page.take(limit)
                .map { (agent, versions) -> AgentResponse.from(agent, developerName(agent.developerId), versions) },
            nextCursor = page.getOrNull(limit)?.first?.id,
        )
    }

    @Transactional
    fun getBySlug(slug: String): AgentResponse {
        return agentRepository.findBySlug(slug)?.let { agent ->
            AgentResponse.from(agent, developerName(agent.developerId), versions(agent.id))
        } ?: throw ApiException("AGENT_NOT_FOUND", "Agent was not found", 404, mapOf("slug" to slug))
    }

    @Transactional
    fun create(request: CreateAgentRequest): AgentResponse {
        validateVersion(
            request.semver,
            request.endpoint,
            request.priceAtomic,
            request.network,
            request.asset,
            request.payTo
        )
        val developer = requireDeveloper(request.developerId)
        val agent = Agent(UUID.randomUUID(), developer.id, request.slug, request.name, request.description)
        return try {
            val saved = agentRepository.save(agent)
            agentVersionRepository.save(
                AgentVersion(
                    UUID.randomUUID(),
                    saved.id,
                    request.semver,
                    request.endpoint,
                    BigInteger(request.priceAtomic),
                    request.network,
                    request.asset,
                    request.payTo
                )
            )
            AgentResponse.from(saved, developer.displayName, versions(saved.id))
        } catch (exception: DataIntegrityViolationException) {
            throw ApiException("AGENT_ALREADY_EXISTS", "Agent slug or version already exists", 409)
        }
    }

    @Transactional
    fun update(id: UUID, request: UpdateAgentRequest): AgentResponse {
        if (request.isEmpty()) {
            throw ApiException("VALIDATION_ERROR", "At least one field is required", 422)
        }
        val agent = requireAgent(id)
        agent.updateMetadata(request.name ?: agent.name, request.description ?: agent.description)
        return AgentResponse.from(agent, developerName(agent.developerId), versions(agent.id))
    }

    @Transactional
    fun createVersion(agentId: UUID, request: CreateAgentVersionRequest): AgentVersionResponse {
        validateVersion(
            request.semver,
            request.endpoint,
            request.priceAtomic,
            request.network,
            request.asset,
            request.payTo
        )
        val agent = requireAgent(agentId)
        if (versionBySemver(agentId, request.semver) != null) {
            throw ApiException("AGENT_VERSION_ALREADY_EXISTS", "Agent version already exists", 409)
        }
        val version = agentVersionRepository.save(
            AgentVersion(
                UUID.randomUUID(),
                agent.id,
                request.semver,
                request.endpoint,
                BigInteger(request.priceAtomic),
                request.network,
                request.asset,
                request.payTo
            )
        )
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun publish(versionId: UUID): AgentVersionResponse {
        val version = agentVersionRepository.findWithAgentById(versionId)
            ?: throw ApiException("AGENT_VERSION_NOT_FOUND", "Agent version was not found", 404)
        if (version.status != AgentVersionStatus.DRAFT) {
            throw ApiException(
                "INVALID_VERSION_TRANSITION",
                "Only DRAFT versions can be published",
                409,
                mapOf("status" to version.status)
            )
        }
        endpointPolicy.validate(version.endpoint)
        version.publish()
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun disable(versionId: UUID): AgentVersionResponse {
        val version = agentVersionRepository.findWithAgentById(versionId)
            ?: throw ApiException("AGENT_VERSION_NOT_FOUND", "Agent version was not found", 404)
        if (version.status != AgentVersionStatus.ACTIVE) {
            throw ApiException(
                "INVALID_VERSION_TRANSITION",
                "Only ACTIVE versions can be disabled",
                409,
                mapOf("status" to version.status)
            )
        }
        version.disable()
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun delete(id: UUID) {
        val agent = requireAgent(id)
        val versionCount = versions(agent.id).size
        if (versionCount > 0) {
            throw ApiException(
                "AGENT_HAS_VERSIONS",
                "An Agent with versions cannot be deleted; disable its ACTIVE versions instead",
                409,
                mapOf("versionCount" to versionCount)
            )
        }
        agentRepository.delete(agent)
    }

    private fun validateVersion(
        semver: String,
        endpoint: String,
        priceAtomic: String,
        network: String,
        asset: String,
        payTo: String
    ) {
        if (!SEMVER.matches(semver)) {
            throw ApiException("INVALID_SEMVER", "semver must be a valid semantic version", 400)
        }
        endpointPolicy.validate(endpoint)
        if (priceAtomic.toBigIntegerOrNull() == null || BigInteger(priceAtomic) < BigInteger.ZERO) {
            throw ApiException("INVALID_PRICE", "priceAtomic must be a non-negative atomic integer", 400)
        }
        if (network.isBlank() || asset.isBlank() || payTo.isBlank()) {
            throw ApiException("INVALID_PAYMENT_TERMS", "network, asset and payTo are required", 400)
        }
    }

    private fun requireLimit(limit: Int) {
        if (limit !in 1..50) {
            throw ApiException("VALIDATION_ERROR", "limit must be between 1 and 50", 422)
        }
    }

    private fun developerName(id: UUID): String {
        return requireDeveloper(id).displayName
    }

    companion object {
        private val SEMVER =
            Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    }
}
