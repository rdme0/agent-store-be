package com.agentstore.dependency.service

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.dto.request.UpdateDependencyRequest
import com.agentstore.dependency.dto.response.DependencyResponse
import com.agentstore.dependency.model.entity.AgentDependency
import com.agentstore.dependency.model.entity.AgentDependencyAllowedProvider
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.repository.AgentDependencyAllowedProviderRepository
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
    private val capabilityService: AgentCapabilityService,
    private val allowedProviderRepository: AgentDependencyAllowedProviderRepository,
) {
    @Transactional
    fun list(sourceVersionId: UUID): List<DependencyResponse> {
        requireVersion(sourceVersionId)
        return dependencyRepository.findAllBySourceVersionIdOrderByIdAsc(sourceVersionId).map(::response)
    }

    @Transactional
    fun create(sourceVersionId: UUID, request: CreateDependencyRequest): DependencyResponse {
        val source = requireDraft(sourceVersionId)
        val isFunctionDependency = isFunctionDependency(request = request)
        if (isFunctionDependency) {
            validateFunctionDependency(
                request = request,
            )
        } else {
            validateDirectDependency(request = request)
        }
        request.targetAgentId?.let { targetAgentId ->
            val target = agentService.requireAgent(targetAgentId)
            cycleValidator.validate(
                sourceAgentId = source.agentId,
                targetAgentId = target.id,
                sourceCode = sourceCode(agentId = source.agentId),
                targetCode = target.code,
            )
        }
        val functionContractId = request.functionContractId
        functionContractId?.let(capabilityService::requireCapability)
        val constraint = resolver.normalizeConstraint(constraint = request.versionConstraint)
        validateLimits(maxPriceAtomic = request.maxPriceAtomic, maxCalls = request.maxCalls)
        val dependency = AgentDependency(
            UUID.randomUUID(),
            source.id,
            request.targetAgentId,
            constraint,
            request.required,
            BigInteger(request.maxPriceAtomic),
            request.maxCalls,
        )
        if (isFunctionDependency) {
            dependency.configureFunctionSelection(
                functionContractId,
                request.providerScope,
                request.selectionStrategy,
                request.minReliabilityPercent,
                request.maxP95LatencyMillis,
            )
        }
        return try {
            val saved = dependencyRepository.saveAndFlush(dependency)
            saveAllowedProviders(
                dependencyId = saved.id,
                providerScope = request.providerScope,
                agentIds = request.allowedProviderAgentIds,
            )
            response(saved)
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
        val constraint = resolver.normalizeConstraint(
            constraint = request.versionConstraint ?: dependency.versionConstraint,
        )
        val maxPrice = request.maxPriceAtomic?.let { BigInteger(it) } ?: dependency.maxPriceAtomic
        val maxCalls = request.maxCalls ?: dependency.maxCalls
        validateLimits(maxPriceAtomic = maxPrice.toString(), maxCalls = maxCalls)
        dependency.targetAgentId?.let { targetAgentId ->
            val target = agentService.requireAgent(targetAgentId)
            cycleValidator.validate(
                sourceAgentId = source.agentId,
                targetAgentId = target.id,
                sourceCode = sourceCode(agentId = source.agentId),
                targetCode = target.code,
            )
        }
        dependency.update(
            constraint,
            request.required ?: dependency.isRequired,
            maxPrice,
            maxCalls,
        )
        if (dependency.functionContractId != null) {
            updateFunctionSelection(dependency = dependency, request = request)
            if (request.allowedProviderAgentIds != null) {
                saveAllowedProviders(
                    dependencyId = dependency.id,
                    providerScope = dependency.providerScope,
                    agentIds = request.allowedProviderAgentIds,
                )
            }
        }
        return response(dependency)
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

    private fun validateFunctionDependency(request: CreateDependencyRequest) {
        val functionContractId = request.functionContractId
            ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        val providerScope = request.providerScope
            ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        capabilityService.requireCapability(id = functionContractId)
        when (providerScope) {
            ProviderScope.PINNED -> {
                if (request.targetAgentId == null) {
                    throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                }
                if (request.selectionStrategy != null || !request.allowedProviderAgentIds.isNullOrEmpty()) {
                    throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                }
            }
            ProviderScope.ALLOWLIST -> {
                if (request.targetAgentId != null || request.allowedProviderAgentIds.isNullOrEmpty()) {
                    throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                }
                request.allowedProviderAgentIds.forEach(agentService::requireAgent)
                validateStrategy(request = request)
            }
            ProviderScope.MARKETPLACE -> {
                if (request.targetAgentId != null || !request.allowedProviderAgentIds.isNullOrEmpty()) {
                    throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
                }
                validateStrategy(request = request)
            }
        }
    }

    private fun validateStrategy(request: CreateDependencyRequest) {
        val strategy = request.selectionStrategy ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
    }

    private fun updateFunctionSelection(dependency: AgentDependency, request: UpdateDependencyRequest) {
        if (request.providerScope != null && request.providerScope != dependency.providerScope) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        val providerScope = request.providerScope ?: dependency.providerScope
        val selectionStrategy = request.selectionStrategy ?: dependency.selectionStrategy
        if (providerScope == null) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        if (providerScope == ProviderScope.PINNED && selectionStrategy != null) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        if (providerScope != ProviderScope.PINNED && selectionStrategy == null) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        dependency.configureFunctionSelection(
            dependency.functionContractId,
            providerScope,
            selectionStrategy,
            request.minReliabilityPercent ?: dependency.minReliabilityPercent,
            request.maxP95LatencyMillis ?: dependency.maxP95LatencyMillis,
        )
    }

    private fun saveAllowedProviders(
        dependencyId: UUID,
        providerScope: ProviderScope?,
        agentIds: Set<UUID>?,
    ) {
        if (providerScope == null || agentIds == null) {
            return
        }
        allowedProviderRepository.deleteAllByIdDependencyId(dependencyId)
        if (providerScope == ProviderScope.ALLOWLIST) {
            allowedProviderRepository.saveAll(
                agentIds.map { agentId ->
                    AgentDependencyAllowedProvider(dependencyId, agentId)
                },
            )
        }
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

    private fun sourceCode(agentId: UUID): String {
        return agentService.codeForAgent(agentId)
    }

    private fun validateDirectDependency(request: CreateDependencyRequest) {
        if (request.targetAgentId == null) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun isFunctionDependency(request: CreateDependencyRequest): Boolean {
        return request.functionContractId != null || request.providerScope != null ||
            request.selectionStrategy != null || request.allowedProviderAgentIds != null ||
            request.minReliabilityPercent != null || request.maxP95LatencyMillis != null
    }

    private fun response(dependency: AgentDependency): DependencyResponse {
        val target = dependency.targetAgentId?.let(agentService::requireAgent)
        val functionContract = dependency.functionContractId?.let(capabilityService::requireCapability)
        return DependencyResponse.from(
            dependency = dependency,
            targetAgentCode = target?.code,
            functionCode = functionContract?.key,
            functionContractVersion = functionContract?.contractVersion,
        )
    }
}
