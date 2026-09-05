package com.agentstore.agent

import com.agentstore.agent.codec.AgentListCursorCodec
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.model.entity.FunctionContract
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.resolver.AgentEndpointAddressResolver
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.AgentService
import com.agentstore.agent.service.FunctionContractReader
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.support.ExplicitProxy
import com.agentstore.support.emptyReadinessRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetAddress
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class AgentServiceFunctionContractTest {
    @Test
    fun `function contract response format mismatch is rejected before version persistence`() {
        val functionContractId = UUID.randomUUID()
        val schema = jacksonObjectMapper().readTree("""{"type":"object"}""")
        val functionContract = FunctionContract(
            functionContractId,
            "finance.stock-news-analysis",
            "1.0.0",
            "News",
            "News contract",
            AgentResponseFormat.JSON,
            schema,
            schema,
        )
        val functionContractReader = ExplicitProxy(FunctionContractReader::class.java).apply {
            answer(methodName = "requireFunctionContract") { functionContract }
        }
        val service = AgentService(
            agentRepository = ExplicitProxy(AgentRepository::class.java).value,
            agentVersionRepository = ExplicitProxy(AgentVersionRepository::class.java).value,
            developerRepository = ExplicitProxy(DeveloperRepository::class.java).value,
            endpointPolicy = endpointPolicy(),
            cursorCodec = cursorCodec(),
            functionContractService = functionContractReader.value,
            readinessRepository = emptyReadinessRepository(),
        )

        val exception = assertThrows(DomainClientException::class.java) {
            service.createVersion(
                agentId = UUID.randomUUID(),
                request = CreateAgentVersionRequest(
                    semver = "1.0.0",
                    endpoint = "http://localhost:8090/invoke",
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

    private fun endpointPolicy(): AgentEndpointPolicy {
        return AgentEndpointPolicy(
            environment = MockEnvironment().apply { setActiveProfiles("test") },
            addressResolver = AgentEndpointAddressResolver {
                listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            },
        )
    }

    private fun cursorCodec(): AgentListCursorCodec {
        return AgentListCursorCodec(
            objectMapper = jacksonObjectMapper().findAndRegisterModules(),
            properties = AgentStoreProperties(
                serviceName = "agent-store-api",
                apiVersion = "0.1.0",
                runtimeCallbackBaseUrl = "http://127.0.0.1:8080",
                demoAgentBaseUrl = "http://127.0.0.1:8090",
                corsOrigins = listOf("http://localhost:5173"),
                runtimeTokenSecret = "test-cursor-secret",
                bithumbApiUrl = "https://api.bithumb.com",
                bithumbRequestTimeout = Duration.ofSeconds(2),
                bithumbCacheTtl = Duration.ofSeconds(60),
                bithumbStaleTtl = Duration.ofMinutes(15),
            ),
        )
    }
}
