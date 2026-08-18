package com.agentstore.dependency.service

import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.web.ApiException
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.dto.request.UpdateDependencyRequest
import com.agentstore.dependency.dto.response.DependencyResponse
import com.agentstore.dependency.model.entity.AgentDependency
import com.agentstore.dependency.repository.AgentDependencyRepository
import com.agentstore.dependency.resolver.CycleValidator
import com.agentstore.dependency.resolver.DependencyResolver
import jakarta.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.util.UUID

@Service
class DependencyService(
    private val dependencyRepository: AgentDependencyRepository,
    private val agentVersionRepository: AgentVersionRepository,
    private val agentRepository: AgentRepository,
    private val resolver: DependencyResolver,
    private val cycleValidator: CycleValidator,
) {
    @Transactional
    fun list(sourceVersionId: UUID): List<DependencyResponse> {
        requireVersion(sourceVersionId)
        return dependencyRepository.findAllBySourceVersionId(sourceVersionId).map { dependency ->
            val target = agentRepository.findById(dependency.targetAgentId).orElseThrow { ApiException("AGENT_NOT_FOUND", "Target Agent was not found", 404) }
            DependencyResponse.from(dependency, target.slug)
        }
    }

    @Transactional
    fun create(sourceVersionId: UUID, request: CreateDependencyRequest): DependencyResponse {
        val source = requireDraft(sourceVersionId)
        val target = agentRepository.findById(request.targetAgentId).orElseThrow { ApiException("AGENT_NOT_FOUND", "Target Agent was not found", 404) }
        cycleValidator.validate(source.agentId, target.id, sourceSlug(source.agentId), target.slug)
        resolver.validateConstraint(request.versionConstraint)
        validateLimits(request.maxPriceAtomic, request.maxCalls)
        val dependency = AgentDependency(UUID.randomUUID(), source.id, target.id, request.versionConstraint, request.required, BigInteger(request.maxPriceAtomic), request.maxCalls)
        return try {
            DependencyResponse.from(dependencyRepository.saveAndFlush(dependency), target.slug)
        } catch (exception: DataIntegrityViolationException) {
            throw ApiException("DEPENDENCY_ALREADY_EXISTS", "Dependency already exists", 409)
        }
    }

    @Transactional
    fun update(sourceVersionId: UUID, dependencyId: UUID, request: UpdateDependencyRequest): DependencyResponse {
        val source = requireDraft(sourceVersionId)
        if (request.isEmpty()) throw ApiException("VALIDATION_ERROR", "At least one field is required", 422)
        val dependency = dependencyRepository.findByIdAndSourceVersionId(dependencyId, sourceVersionId)
            ?: throw ApiException("DEPENDENCY_NOT_FOUND", "The dependency was not found", 404)
        val constraint = request.versionConstraint ?: dependency.versionConstraint
        resolver.validateConstraint(constraint)
        val maxPrice = request.maxPriceAtomic?.let { BigInteger(it) } ?: dependency.maxPriceAtomic
        val maxCalls = request.maxCalls ?: dependency.maxCalls
        validateLimits(maxPrice.toString(), maxCalls)
        val target = agentRepository.findById(dependency.targetAgentId).orElseThrow { ApiException("AGENT_NOT_FOUND", "Target Agent was not found", 404) }
        cycleValidator.validate(source.agentId, target.id, sourceSlug(source.agentId), target.slug)
        dependency.update(constraint, request.required ?: dependency.isRequired, maxPrice, maxCalls)
        return DependencyResponse.from(dependency, target.slug)
    }

    @Transactional
    fun remove(sourceVersionId: UUID, dependencyId: UUID) {
        requireDraft(sourceVersionId)
        val dependency = dependencyRepository.findByIdAndSourceVersionId(dependencyId, sourceVersionId)
            ?: throw ApiException("DEPENDENCY_NOT_FOUND", "The dependency was not found", 404)
        dependencyRepository.delete(dependency)
    }

    private fun requireVersion(id: UUID) = agentVersionRepository.findWithAgentById(id)
        ?: throw ApiException("AGENT_VERSION_NOT_FOUND", "Agent version was not found", 404)

    private fun requireDraft(id: UUID) = requireVersion(id).also {
        if (it.status != AgentVersionStatus.DRAFT) throw ApiException("ACTIVE_VERSION_IMMUTABLE", "Dependencies can only be changed on DRAFT versions", 409, mapOf("status" to it.status))
    }

    private fun validateLimits(maxPriceAtomic: String, maxCalls: Int) {
        if (maxPriceAtomic.toBigIntegerOrNull() == null || BigInteger(maxPriceAtomic) < BigInteger.ZERO) throw ApiException("INVALID_PRICE", "maxPriceAtomic must be non-negative", 400)
        if (maxCalls !in 1..5) throw ApiException("INVALID_MAX_CALLS", "maxCalls must be between 1 and 5", 400)
    }

    private fun sourceSlug(agentId: UUID): String = agentRepository.findById(agentId).orElseThrow { ApiException("AGENT_NOT_FOUND", "Agent was not found", 404) }.slug
}
