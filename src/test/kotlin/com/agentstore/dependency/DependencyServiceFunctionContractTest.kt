package com.agentstore.dependency

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.repository.AgentDependencyAllowedProviderRepository
import com.agentstore.dependency.repository.AgentDependencyRepository
import com.agentstore.dependency.resolver.CycleValidator
import com.agentstore.dependency.resolver.DependencyResolver
import com.agentstore.dependency.service.DependencyService
import java.math.BigInteger
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DependencyServiceFunctionContractTest {
    @Test
    fun `dependency must declare either a direct target or a complete function provider rule`() {
        val agentService = mock(AgentService::class.java)
        val sourceVersionId = UUID.randomUUID()
        `when`(agentService.requireVersion(sourceVersionId)).thenReturn(
            AgentVersion(
                sourceVersionId,
                UUID.randomUUID(),
                null,
                "1.0.0",
                "https://source.example.com/invoke",
                BigInteger.ONE,
                "eip155:84532",
                "USDC",
                "0x0000000000000000000000000000000000000001",
                AgentResponseFormat.JSON,
            ),
        )
        val service = DependencyService(
            mock(AgentDependencyRepository::class.java),
            agentService,
            mock(DependencyResolver::class.java),
            mock(CycleValidator::class.java),
            mock(AgentCapabilityService::class.java),
            mock(AgentDependencyAllowedProviderRepository::class.java),
        )
        val functionContractId = UUID.randomUUID()
        val requests = listOf(
            request(
                targetAgentId = null,
                functionContractId = null,
                providerScope = null,
                selectionStrategy = null,
                explorationPercent = null,
            ),
            request(
                targetAgentId = null,
                functionContractId = functionContractId,
                providerScope = null,
                selectionStrategy = null,
                explorationPercent = 0,
            ),
            request(
                targetAgentId = null,
                functionContractId = functionContractId,
                providerScope = ProviderScope.PINNED,
                selectionStrategy = null,
                explorationPercent = 0,
            ),
            request(
                targetAgentId = UUID.randomUUID(),
                functionContractId = functionContractId,
                providerScope = ProviderScope.MARKETPLACE,
                selectionStrategy = ProviderSelectionStrategy.LOWEST_PRICE,
                explorationPercent = 0,
            ),
        )

        requests.forEach { request ->
            val exception = assertThrows(DomainClientException::class.java) {
                service.create(sourceVersionId = sourceVersionId, request = request)
            }
            assertEquals(ErrorCode.INVALID_INPUT_VALUE, exception.errorCode)
        }
    }

    private fun request(
        targetAgentId: UUID?,
        functionContractId: UUID?,
        providerScope: ProviderScope?,
        selectionStrategy: ProviderSelectionStrategy?,
        explorationPercent: Int?,
    ): CreateDependencyRequest {
        return CreateDependencyRequest(
            targetAgentId = targetAgentId,
            functionContractId = functionContractId,
            providerScope = providerScope,
            selectionStrategy = selectionStrategy,
            explorationPercent = explorationPercent,
            versionConstraint = "*",
            maxPriceAtomic = "1000",
        )
    }
}
