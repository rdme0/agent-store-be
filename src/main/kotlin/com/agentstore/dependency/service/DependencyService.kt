package com.agentstore.dependency.service

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.dto.request.UpdateDependencyRequest
import com.agentstore.dependency.dto.response.DependencyResponse
import com.agentstore.dependency.model.entity.AgentDependency
import com.agentstore.dependency.repository.AgentDependencyRepository
import com.agentstore.dependency.resolver.CycleValidator
import com.agentstore.dependency.resolver.DependencyResolver
import jakarta.transaction.Transactional
import java.math.BigInteger
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class DependencyService(
    private val dependencyRepository: AgentDependencyRepository,
    private val agentService: AgentService,
    private val resolver: DependencyResolver,
    private val cycleValidator: CycleValidator,
) {
    @Transactional
    fun list(sourceVersionId: UUID): List<DependencyResponse> {
        requireVersion(sourceVersionId)
        return dependencyRepository.findAllBySourceVersionId(sourceVersionId).map { dependency ->
            val target = agentService.requireAgent(dependency.targetAgentId)
            DependencyResponse.from(dependency = dependency, targetAgentSlug = target.slug)
        }
    }

    @Transactional
    fun create(sourceVersionId: UUID, request: CreateDependencyRequest): DependencyResponse {
        val source = requireDraft(sourceVersionId)
        val target = agentService.requireAgent(request.targetAgentId)
        cycleValidator.validate(
            sourceAgentId = source.agentId,
            targetAgentId = target.id,
            sourceSlug = sourceSlug(agentId = source.agentId),
            targetSlug = target.slug,
        )
        resolver.validateConstraint(request.versionConstraint)
        validateLimits(maxPriceAtomic = request.maxPriceAtomic, maxCalls = request.maxCalls)
        val dependency = AgentDependency(
            UUID.randomUUID(),
            source.id,
            target.id,
            request.versionConstraint,
            request.required,
            BigInteger(request.maxPriceAtomic),
            request.maxCalls
        )
        return try {
            DependencyResponse.from(
                dependency = dependencyRepository.saveAndFlush(dependency),
                targetAgentSlug = target.slug,
            )
        } catch (exception: DataIntegrityViolationException) {
            throw DomainClientException(ErrorCode.DEPENDENCY_ALREADY_EXISTS)
        }
    }

    @Transactional
    fun update(
        sourceVersionId: UUID,
        dependencyId: UUID,
        request: UpdateDependencyRequest
    ): DependencyResponse {
        val source = requireDraft(sourceVersionId)
        if (request.isEmpty()) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        val dependency =
            dependencyRepository.findByIdAndSourceVersionId(
                id = dependencyId,
                sourceVersionId = sourceVersionId,
            )
                ?: throw DomainClientException(ErrorCode.DEPENDENCY_NOT_FOUND)
        val constraint = request.versionConstraint ?: dependency.versionConstraint
        resolver.validateConstraint(constraint)
        val maxPrice = request.maxPriceAtomic?.let { BigInteger(it) } ?: dependency.maxPriceAtomic
        val maxCalls = request.maxCalls ?: dependency.maxCalls
        validateLimits(maxPriceAtomic = maxPrice.toString(), maxCalls = maxCalls)
        val target = agentService.requireAgent(dependency.targetAgentId)
        cycleValidator.validate(
            sourceAgentId = source.agentId,
            targetAgentId = target.id,
            sourceSlug = sourceSlug(agentId = source.agentId),
            targetSlug = target.slug,
        )
        dependency.update(constraint, request.required ?: dependency.isRequired, maxPrice, maxCalls)
        return DependencyResponse.from(dependency = dependency, targetAgentSlug = target.slug)
    }

    @Transactional
    fun remove(sourceVersionId: UUID, dependencyId: UUID) {
        requireDraft(sourceVersionId)
        val dependency =
            dependencyRepository.findByIdAndSourceVersionId(
                id = dependencyId,
                sourceVersionId = sourceVersionId,
            )
                ?: throw DomainClientException(ErrorCode.DEPENDENCY_NOT_FOUND)
        dependencyRepository.delete(dependency)
    }

    private fun requireVersion(id: UUID): AgentVersion {
        return agentService.requireVersion(id)
    }

    private fun requireDraft(id: UUID): AgentVersion {
        val version = requireVersion(id)
        if (version.status != AgentVersionStatus.DRAFT) {
            throw DomainClientException(ErrorCode.ACTIVE_VERSION_IMMUTABLE)
        }
        return version
    }

    private fun validateLimits(maxPriceAtomic: String, maxCalls: Int) {
        if (maxPriceAtomic.toBigIntegerOrNull() == null || BigInteger(maxPriceAtomic) < BigInteger.ZERO) {
            throw DomainClientException(ErrorCode.DEPENDENCY_INVALID_PRICE)
        }
        if (maxCalls !in 1..5) {
            throw DomainClientException(ErrorCode.INVALID_MAX_CALLS)
        }
    }

    private fun sourceSlug(agentId: UUID): String {
        return agentService.slugForAgent(agentId)
    }
}
