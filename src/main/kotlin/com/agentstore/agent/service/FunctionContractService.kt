package com.agentstore.agent.service

import com.agentstore.agent.dto.request.CreateFunctionContractRequest
import com.agentstore.agent.dto.response.FunctionContractResponse
import com.agentstore.agent.dto.response.FunctionProviderMetricResponse
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.execution.service.ProviderMetricService
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class FunctionContractService(
    private val capabilityService: AgentCapabilityService,
    private val providerMetricService: ProviderMetricService,
) {
    companion object {
        private val FUNCTION_CODE = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    }

    fun create(request: CreateFunctionContractRequest): FunctionContractResponse {
        if (!FUNCTION_CODE.matches(request.code)) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        return FunctionContractResponse.from(
            contract = capabilityService.create(request = request),
        )
    }

    fun list(): List<FunctionContractResponse> {
        return capabilityService.list().map(FunctionContractResponse::from)
    }

    fun get(id: UUID): FunctionContractResponse {
        return FunctionContractResponse.from(contract = capabilityService.requireCapability(id = id))
    }

    fun providerMetrics(id: UUID): List<FunctionProviderMetricResponse> {
        val providers = capabilityService.providers(id = id)
        val metrics = providerMetricService.performance(
            functionContractId = id,
            versionIds = providers.map { provider -> provider.versionId },
        )
        return providers.map { provider ->
            val performance = metrics[provider.versionId]
            FunctionProviderMetricResponse(
                agentId = provider.agentId,
                agentCode = provider.agentCode,
                agentName = provider.agentName,
                versionId = provider.versionId,
                semver = provider.semver,
                priceAtomic = provider.priceAtomic,
                observationCount = performance?.observationCount ?: 0,
                reliabilityPercent = performance?.reliabilityPercent,
                p95LatencyMillis = performance?.p95LatencyMillis,
                contractCompliancePercent = performance?.contractCompliancePercent,
                mature = performance?.isMature ?: false,
            )
        }
    }
}
