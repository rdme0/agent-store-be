package com.agentstore.agent

import com.agentstore.agent.codec.AgentListCursorCodec
import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.resolver.AgentEndpointAddressResolver
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.AgentService
import com.agentstore.agent.service.FunctionContractReader
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.support.ExplicitProxy
import com.agentstore.support.emptyReadinessRepository
import com.agentstore.x402.client.X402AgentClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class AgentEndpointAdmissionTest {
    private companion object {
        const val RECEIVER = "0x0000000000000000000000000000000000000001"
    }

    @Test
    fun `registration and draft version reject unsafe endpoints before persistence`() {
        val service = agentService()
        val request = CreateAgentRequest(
            UUID.randomUUID(),
            "unsafe-agent",
            "unsafe",
            "unsafe",
            "1.0.0",
            "https://agent.example.com",
            "1",
            "eip155:84532",
            "USDC",
            RECEIVER
        )
        val versionRequest =
            CreateAgentVersionRequest(
                "1.0.0",
                "https://agent.example.com",
                "1",
                "eip155:84532",
                "USDC",
                RECEIVER
            )

        assertUnsafe { service.create(request) }
        assertUnsafe { service.createVersion(UUID.randomUUID(), versionRequest) }
    }

    @Test
    fun `publish rejects a persisted unsafe draft without activating it`() {
        val draft = AgentVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "1.0.0",
            "https://agent.example.com",
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            RECEIVER
        )
        val versions = ExplicitProxy(AgentVersionRepository::class.java).apply {
            answer(methodName = "findWithAgentById") { draft }
        }
        val agents = ExplicitProxy(AgentRepository::class.java).apply {
            answer(methodName = "findById") {
                Optional.of(Agent(draft.agentId, UUID.randomUUID(), "draft", "draft", "draft"))
            }
        }
        val functionContractReader = ExplicitProxy(FunctionContractReader::class.java)
        val service = AgentService(
            agentRepository = agents.value,
            agentVersionRepository = versions.value,
            developerRepository = ExplicitProxy(DeveloperRepository::class.java).value,
            endpointPolicy = productionPolicy(),
            cursorCodec = cursorCodec(),
            functionContractService = functionContractReader.value,
            readinessRepository = emptyReadinessRepository(),
        )

        assertUnsafe { service.publish(draft.id) }
        assertEquals("DRAFT", draft.status.name)
    }

    @Test
    fun `production endpoint policy rejects a private address before outbound calls`() {
        assertUnsafe { productionPolicy().validate("https://agent.example.com/invoke") }
    }

    @Test
    fun `native x402 client rejects unsafe endpoint before outbound request construction`() {
        val client = X402AgentClient(
            endpointPolicy = productionPolicy(),
            pinnedClientFactory = PinnedAgentRestClientFactory(),
        )

        assertUnsafe { client.prepare(endpoint = "https://agent.example.com") }
    }

    @Test
    fun `native x402 client pins loopback connection preserves host and does not follow redirects`() {
        val redirectedRequests = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/target") { exchange ->
                redirectedRequests.incrementAndGet()
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("{}".toByteArray()) }
            }
            start()
        }
        var receivedHost: String? = null
        val redirect = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/invoke") { exchange ->
                receivedHost = exchange.requestHeaders.getFirst("Host")
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${target.address.port}/target"
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }
        try {
            val policy = AgentEndpointPolicy(
                MockEnvironment().apply { setActiveProfiles("test") },
                AgentEndpointAddressResolver { error("loopback must not use DNS") })
            val client = X402AgentClient(
                endpointPolicy = policy,
                pinnedClientFactory = PinnedAgentRestClientFactory(),
            )
            val request = PaymentInvocationRequestDto(
                paymentAttemptId = "attempt",
                idempotencyKey = "key",
                invocationToken = "token",
                endpoint = "http://localhost:${redirect.address.port}/invoke",
                amountAtomic = "1",
                maxPriceAtomic = "1",
                network = "eip155:84532",
                asset = "USDC",
                payTo = RECEIVER,
                body = emptyMap<String, Any>(),
            )

            val response = client.post(
                connection = client.prepare(endpoint = request.endpoint),
                request = request,
                body = ObjectMapper().writeValueAsBytes(request.body),
                paymentSignature = null,
                deadline = System.nanoTime() + 30_000_000_000,
            )

            assertEquals(302, response.status)
            assertEquals("localhost:${redirect.address.port}", receivedHost)
            assertEquals(0, redirectedRequests.get())
        } finally {
            redirect.stop(0)
            target.stop(0)
        }
    }

    private fun agentService(): AgentService {
        return AgentService(
            ExplicitProxy(AgentRepository::class.java).value,
            ExplicitProxy(AgentVersionRepository::class.java).value,
            ExplicitProxy(DeveloperRepository::class.java).value,
            productionPolicy(),
            cursorCodec(),
            ExplicitProxy(FunctionContractReader::class.java).value,
            emptyReadinessRepository(),
        )
    }

    private fun productionPolicy(): AgentEndpointPolicy {
        return AgentEndpointPolicy(
            MockEnvironment().apply { setActiveProfiles("prod") },
            AgentEndpointAddressResolver {
                listOf(
                    InetAddress.getByAddress(
                        byteArrayOf(
                            10,
                            0,
                            0,
                            1
                        )
                    )
                )
            },
        )
    }

    private fun cursorCodec(): AgentListCursorCodec {
        return AgentListCursorCodec(
            objectMapper = ObjectMapper(),
            properties = AgentStoreProperties(
                serviceName = "agent-store-api",
                apiVersion = "0.1.0",
                runtimeCallbackBaseUrl = "http://127.0.0.1:8080",
                demoAgentBaseUrl = "http://127.0.0.1:8090",
                corsOrigins = listOf("http://localhost:5173"),
                runtimeTokenSecret = "test-cursor-secret",
                bithumbApiUrl = "https://api.bithumb.com",
                bithumbRequestTimeout = java.time.Duration.ofSeconds(2),
                bithumbCacheTtl = java.time.Duration.ofSeconds(60),
                bithumbStaleTtl = java.time.Duration.ofMinutes(15),
            ),
        )
    }

    private fun assertUnsafe(action: () -> Unit) {
        assertEquals(
            "AGENT_400_005",
            assertThrows(DomainClientException::class.java, action).errorCode.code
        )
    }

}
