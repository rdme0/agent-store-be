package com.agentstore.agent

import com.agentstore.agent.codec.AgentListCursorCodec
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.model.entity.FunctionContract
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.FunctionContractService
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class AgentServiceFunctionContractTest {
    @Test
    fun `function contract response format mismatch is rejected before version persistence`() {
        val functionContractService = mock(FunctionContractService::class.java)
        val versionRepository = mock(AgentVersionRepository::class.java)
        val functionContractId = UUID.randomUUID()
        val schema = jacksonObjectMapper().readTree("""{"type":"object"}""")
        `when`(functionContractService.requireFunctionContract(functionContractId)).thenReturn(
            FunctionContract(
                functionContractId,
                "finance.stock-news-analysis",
                "1.0.0",
                "News",
                "News contract",
                AgentResponseFormat.JSON,
                schema,
                schema,
            ),
        )
        val service = AgentService(
            mock(AgentRepository::class.java),
            versionRepository,
            mock(DeveloperRepository::class.java),
            mock(AgentEndpointPolicy::class.java),
            mock(AgentListCursorCodec::class.java),
            functionContractService,
        )

        val exception = assertThrows(DomainClientException::class.java) {
            service.createVersion(
                agentId = UUID.randomUUID(),
                request = CreateAgentVersionRequest(
                    semver = "1.0.0",
                    endpoint = "https://agent.example.com/invoke",
                    priceAtomic = "1000",
                    network = "eip155:84532",
                    asset = "USDC",
                    payTo = "0x0000000000000000000000000000000000000001",
                    responseFormat = AgentResponseFormat.MARKDOWN,
                    functionContractId = functionContractId,
                ),
            )
        }

        assertEquals(ErrorCode.FUNCTION_CONTRACT_RESPONSE_FORMAT_MISMATCH, exception.errorCode)
    }
}
