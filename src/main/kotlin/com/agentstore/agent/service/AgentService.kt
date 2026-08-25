package com.agentstore.agent.service

import com.agentstore.agent.codec.AgentListCursorCodec
import com.agentstore.agent.dto.internal.AgentListCursorPayloadDto
import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.dto.request.UpdateAgentRequest
import com.agentstore.agent.dto.response.AgentListResponse
import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.exception.AgentNotFoundException
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.entity.Developer
import com.agentstore.agent.model.vo.AgentListSort
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.model.vo.AgentUsageType
import com.agentstore.agent.model.vo.AgentView
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import jakarta.transaction.Transactional
import java.math.BigInteger
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

@Service
class AgentService(
    private val agentRepository: AgentRepository,
    private val agentVersionRepository: AgentVersionRepository,
    private val developerRepository: DeveloperRepository,
    private val endpointPolicy: AgentEndpointPolicy,
    private val cursorCodec: AgentListCursorCodec,
    private val capabilityService: AgentCapabilityService,
) {
    companion object {
        private val SEMVER = Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
        )
    }

    /** Agent-owned read boundary used by dependency, quote and revenue use cases. */
    fun requireAgent(id: UUID): Agent {
        return agentRepository.findById(id).orElseThrow {
            AgentNotFoundException()
        }
    }

    fun findByCode(code: String): Agent? {
        return agentRepository.findByCode(code)
    }

    fun findByIdOrNull(id: UUID): Agent? {
        return agentRepository.findById(id).orElse(null)
    }

    fun requireVersion(id: UUID): AgentVersion {
        return agentVersionRepository.findById(id).orElseThrow {
            DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        }
    }

    fun activeVersions(agentId: UUID): List<AgentVersion> {
        return agentVersionRepository.findAllByAgentIdAndStatus(
            agentId = agentId,
            status = AgentVersionStatus.ACTIVE,
        )
    }

    fun draftOrActiveVersions(agentId: UUID): List<AgentVersion> {
        return activeVersions(agentId).ifEmpty {
            agentVersionRepository.findAllByAgentIdAndStatus(
                agentId = agentId,
                status = AgentVersionStatus.DRAFT,
            )
        }
    }

    fun versions(agentId: UUID): List<AgentVersion> {
        return agentVersionRepository.findAllByAgentId(agentId)
    }

    fun versionBySemver(agentId: UUID, semver: String): AgentVersion? {
        return agentVersionRepository.findByAgentIdAndSemver(agentId = agentId, semver = semver)
    }

    fun requireDeveloper(id: UUID): Developer {
        return developerRepository.findById(id).orElseThrow {
            DomainClientException(ErrorCode.AGENT_DEVELOPER_NOT_FOUND)
        }
    }

    fun developerExists(id: UUID): Boolean {
        return developerRepository.existsById(id)
    }

    fun codeForAgent(id: UUID): String {
        return requireAgent(id).code
    }

    fun developerIdForAgent(id: UUID): UUID {
        return requireAgent(id).developerId
    }

    fun developerIdForVersion(versionId: UUID): UUID {
        return developerIdForAgent(requireVersion(versionId).agentId)
    }

    @Transactional
    fun list(
        limit: Int,
        cursor: String?,
        query: String?,
        sort: AgentListSort,
        view: AgentView = AgentView.DEVELOPER,
    ): AgentListResponse {
        requireLimit(limit)
        val normalizedQuery = normalizeQuery(query)
        val decodedCursor = cursor?.let { value ->
            cursorCodec.decode(cursor = value, query = normalizedQuery, sort = sort, view = view)
        }
        val page = marketplaceAgents(
            query = normalizedQuery,
            sort = sort,
            cursor = decodedCursor,
            limit = limit,
            view = view,
        )
        val visibleItems = page.take(limit)
        val dependencyCounts = dependencyCounts(visibleItems.map { agent -> agent.id })
        return AgentListResponse(
            items = visibleItems
                .map { agent ->
                    marketplaceResponse(
                        agent = agent,
                        dependencyCount = dependencyCounts[agent.id] ?: 0,
                    )
                },
            nextCursor = visibleItems.lastOrNull()
                ?.takeIf { page.size > limit }
                ?.let { agent ->
                    cursorCodec.encode(agent = agent, query = normalizedQuery, sort = sort, view = view)
                },
        )
    }

    @Transactional
    fun getByCode(code: String, view: AgentView = AgentView.DEVELOPER): AgentResponse {
        return agentRepository.findByCode(code)?.let { agent ->
            if (view == AgentView.EASY && agent.usageType != AgentUsageType.USER_FACING) {
                throw AgentNotFoundException()
            }
            response(
                agent = agent,
                dependencyCount = dependencyCounts(agentIds = listOf(agent.id))[agent.id] ?: 0,
            )
        } ?: throw AgentNotFoundException()
    }

    @Transactional
    fun create(request: CreateAgentRequest): AgentResponse {
        validateVersion(
            semver = request.semver,
            endpoint = request.endpoint,
            priceAtomic = request.priceAtomic,
            network = request.network,
            asset = request.asset,
            payTo = request.payTo,
        )
        validateCapability(
            capabilityId = request.functionContractId,
            responseFormat = request.responseFormat,
        )
        val developer = requireDeveloper(request.developerId)
        val agent =
            Agent(
                UUID.randomUUID(),
                developer.id,
                request.code,
                request.name,
                request.description,
                request.usageType,
            )
        return try {
            val saved = agentRepository.save(agent)
            agentVersionRepository.save(
                AgentVersion(
                    UUID.randomUUID(),
                    saved.id,
                    request.functionContractId,
                    request.semver,
                    request.endpoint,
                    BigInteger(request.priceAtomic),
                    request.network,
                    request.asset,
                    request.payTo,
                    request.responseFormat
                )
            )
            AgentResponse.from(
                agent = saved,
                developerName = developer.displayName,
                dependencyCount = 0,
                versions = versions(agentId = saved.id),
            )
        } catch (exception: DataIntegrityViolationException) {
            throw DomainClientException(ErrorCode.AGENT_ALREADY_EXISTS)
        }
    }

    @Transactional
    fun update(id: UUID, request: UpdateAgentRequest): AgentResponse {
        if (request.isEmpty()) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        val agent = requireAgent(id)
        val usageType = request.usageType ?: agent.usageType
        val hasActiveJsonVersion = usageType == AgentUsageType.USER_FACING && versions(agent.id)
            .any { version ->
                version.status == AgentVersionStatus.ACTIVE &&
                    version.responseFormat == AgentResponseFormat.JSON
            }
        if (hasActiveJsonVersion) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        agent.updateMetadata(
            request.name ?: agent.name,
            request.description ?: agent.description,
            usageType,
        )
        return response(
            agent = agent,
            dependencyCount = dependencyCounts(agentIds = listOf(agent.id))[agent.id] ?: 0,
        )
    }

    @Transactional
    fun createVersion(agentId: UUID, request: CreateAgentVersionRequest): AgentVersionResponse {
        validateVersion(
            semver = request.semver,
            endpoint = request.endpoint,
            priceAtomic = request.priceAtomic,
            network = request.network,
            asset = request.asset,
            payTo = request.payTo,
        )
        validateCapability(
            capabilityId = request.functionContractId,
            responseFormat = request.responseFormat,
        )
        val agent = requireAgent(agentId)
        if (versionBySemver(agentId = agentId, semver = request.semver) != null) {
            throw DomainClientException(ErrorCode.AGENT_VERSION_ALREADY_EXISTS)
        }
        val version = agentVersionRepository.save(
            AgentVersion(
                UUID.randomUUID(),
                agent.id,
                request.functionContractId,
                request.semver,
                request.endpoint,
                BigInteger(request.priceAtomic),
                request.network,
                request.asset,
                request.payTo,
                request.responseFormat
            )
        )
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun publish(versionId: UUID): AgentVersionResponse {
        val version = agentVersionRepository.findWithAgentById(versionId)
            ?: throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        if (version.status != AgentVersionStatus.DRAFT) {
            throw DomainClientException(ErrorCode.INVALID_VERSION_TRANSITION)
        }
        if (requireAgent(version.agentId).usageType == AgentUsageType.USER_FACING &&
            version.responseFormat == AgentResponseFormat.JSON
        ) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        validateCapability(
            capabilityId = version.capabilityId,
            responseFormat = version.responseFormat,
        )
        endpointPolicy.validate(version.endpoint)
        version.publish()
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun disable(versionId: UUID): AgentVersionResponse {
        val version = agentVersionRepository.findWithAgentById(versionId)
            ?: throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        if (version.status != AgentVersionStatus.ACTIVE) {
            throw DomainClientException(ErrorCode.INVALID_VERSION_TRANSITION)
        }
        version.disable()
        return AgentVersionResponse.from(version)
    }

    @Transactional
    fun delete(id: UUID) {
        val agent = requireAgent(id)
        val versionCount = versions(agent.id).size
        if (versionCount > 0) {
            throw DomainClientException(ErrorCode.AGENT_HAS_VERSIONS)
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
            throw DomainClientException(ErrorCode.INVALID_SEMVER)
        }
        endpointPolicy.validate(endpoint)
        if (priceAtomic.toBigIntegerOrNull() == null || BigInteger(priceAtomic) < BigInteger.ZERO) {
            throw DomainClientException(ErrorCode.AGENT_INVALID_PRICE)
        }
        if (network.isBlank() || asset.isBlank() || payTo.isBlank()) {
            throw DomainClientException(ErrorCode.INVALID_PAYMENT_TERMS)
        }
    }

    @Transactional
    fun attachDraftManifest(versionId: UUID, content: String, sha256: String) {
        val version = requireVersion(versionId)
        if (version.status != AgentVersionStatus.DRAFT) {
            throw DomainClientException(ErrorCode.ACTIVE_VERSION_IMMUTABLE)
        }
        version.replaceManifest(content, sha256)
    }

    fun manifest(versionId: UUID): Pair<String, String>? {
        val version = requireVersion(versionId)
        val content = version.manifestContent ?: return null
        val sha256 = version.manifestSha256 ?: return null
        return content to sha256
    }

    fun activeVersionsForCapability(capabilityId: UUID): List<AgentVersion> {
        return agentVersionRepository.findAllByCapabilityIdAndStatus(
            capabilityId = capabilityId,
            status = AgentVersionStatus.ACTIVE,
        )
    }

    private fun validateCapability(capabilityId: UUID?, responseFormat: AgentResponseFormat) {
        if (capabilityId == null) {
            return
        }
        val capability = capabilityService.requireCapability(id = capabilityId)
        if (capability.responseFormat != responseFormat) {
            throw DomainClientException(ErrorCode.CAPABILITY_RESPONSE_FORMAT_MISMATCH)
        }
    }

    private fun requireLimit(limit: Int) {
        if (limit !in 1..50) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun marketplaceAgents(
        query: String?,
        sort: AgentListSort,
        cursor: AgentListCursorPayloadDto?,
        limit: Int,
        view: AgentView,
    ): List<Agent> {
        val cursorId = cursor?.let { payload -> parseCursorId(payload.id) }
        val pageable = PageRequest.of(0, limit + 1)
        return when (sort) {
            AgentListSort.NEWEST -> agentRepository.findMarketplaceAgentsByCreatedAtDesc(
                query = query,
                status = AgentVersionStatus.ACTIVE,
                usageType = if (view == AgentView.EASY) AgentUsageType.USER_FACING else null,
                hasCursor = cursor != null,
                cursorCreatedAt = cursor?.createdAt,
                cursorId = cursorId,
                pageable = pageable,
            )

            AgentListSort.NAME_ASC -> agentRepository.findMarketplaceAgentsByNameAsc(
                query = query,
                status = AgentVersionStatus.ACTIVE,
                usageType = if (view == AgentView.EASY) AgentUsageType.USER_FACING else null,
                hasCursor = cursor != null,
                cursorNameKey = cursor?.nameKey,
                cursorId = cursorId,
                pageable = pageable,
            )
        }
    }

    private fun normalizeQuery(query: String?): String? {
        if (query != null && query.length > 100) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        return query?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseCursorId(id: String): UUID {
        return try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun dependencyCounts(agentIds: Collection<UUID>): Map<UUID, Int> {
        if (agentIds.isEmpty()) {
            return emptyMap()
        }
        return agentRepository.countDistinctDependenciesByAgentIds(agentIds = agentIds)
            .associate { projection ->
                projection.agentId to projection.dependencyCount.coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
    }

    private fun response(agent: Agent, dependencyCount: Int): AgentResponse {
        return AgentResponse.from(
            agent = agent,
            developerName = developerName(id = agent.developerId),
            dependencyCount = dependencyCount,
            versions = versions(agentId = agent.id),
        )
    }

    private fun marketplaceResponse(agent: Agent, dependencyCount: Int): AgentResponse {
        return AgentResponse.from(
            agent = agent,
            developerName = developerName(id = agent.developerId),
            dependencyCount = dependencyCount,
            versions = activeVersions(agentId = agent.id),
        )
    }

    private fun developerName(id: UUID): String {
        return requireDeveloper(id).displayName
    }

}
