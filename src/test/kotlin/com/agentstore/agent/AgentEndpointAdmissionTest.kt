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
import com.agentstore.agent.service.FunctionContractService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.model.entity.AgentDependency
import com.agentstore.dependency.model.vo.ResolvedEdge
import com.agentstore.dependency.model.vo.ResolvedGraph
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.model.vo.ResolvedVersion
import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.dependency.resolver.CostResolver
import com.agentstore.dependency.resolver.DependencyResolver
import com.agentstore.dependency.service.QuoteService
import com.agentstore.payment.service.KrwEstimateService
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
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
        val versions = mock(AgentVersionRepository::class.java)
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
        `when`(versions.findWithAgentById(draft.id)).thenReturn(draft)
        val agents = mock(AgentRepository::class.java)
        `when`(agents.findById(draft.agentId)).thenReturn(Optional.of(Agent(draft.agentId, UUID.randomUUID(), "draft", "draft", "draft")))
        val service = AgentService(
            agents,
            versions,
            mock(DeveloperRepository::class.java),
            productionPolicy(),
            mock(AgentListCursorCodec::class.java),
            mock(FunctionContractService::class.java),
        )

        assertUnsafe { service.publish(draft.id) }
        assertEquals("DRAFT", draft.status.name)
    }

    @Test
    fun `quote rejects an unsafe optional dependency recursively before snapshot persistence`() {
        val rootAgent = Agent(UUID.randomUUID(), UUID.randomUUID(), "root-agent", "root", "root")
        val root = activeVersion(rootAgent.id, "https://8.8.8.8/invoke")
        val child = activeVersion(UUID.randomUUID(), "https://agent.example.com/invoke")
        val dependency = AgentDependency(
            UUID.randomUUID(),
            root.id,
            child.agentId,
            "*",
            false,
            BigInteger.ONE,
            1
        )
        val graph = ResolvedGraph(
            ResolvedNode(
                resolved(root, "root-agent"),
                listOf(
                    ResolvedEdge(
                        dependency,
                        "optional-child",
                        ResolvedNode(resolved(child, "optional-child"), emptyList())
                    )
                )
            ),
            emptyList(),
        )
        val agentService = mock(AgentService::class.java)
        val resolver = mock(DependencyResolver::class.java)
        `when`(agentService.findByCode("root-agent")).thenReturn(rootAgent)
        `when`(agentService.activeVersions(rootAgent.id)).thenReturn(listOf(root))
        `when`(resolver.matches(version = root.semver, constraint = "*")).thenReturn(true)
        `when`(resolver.newest(listOf(root))).thenReturn(root)
        `when`(
            resolver.resolve(
                rootVersionId = eq(root.id) ?: root.id,
                allowUnresolvedRequired = eq(false),
                allowPriceExceeded = eq(false),
            )
        ).thenReturn(graph)
        val quotePolicy = AgentEndpointPolicy(
            MockEnvironment().apply { setActiveProfiles("prod") },
            AgentEndpointAddressResolver { host ->
                if (host == "8.8.8.8") {
                    listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
                } else listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)))
            },
        )
        val service = QuoteService(
            agentService,
            mock(ExecutionQuoteRepository::class.java),
            resolver,
            CostResolver(),
            quotePolicy,
            ObjectMapper(),
            mock(KrwEstimateService::class.java),
        )

        assertUnsafe { service.create("root-agent", QuoteRequest()) }
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
            mock(AgentRepository::class.java),
            mock(AgentVersionRepository::class.java),
            mock(DeveloperRepository::class.java),
            productionPolicy(),
            mock(AgentListCursorCodec::class.java),
            mock(FunctionContractService::class.java),
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

    private fun activeVersion(agentId: UUID, endpoint: String): AgentVersion {
        return AgentVersion(
            UUID.randomUUID(),
            agentId,
            "1.0.0",
            endpoint,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            RECEIVER
        ).also { it.publish() }
    }

    private fun resolved(version: AgentVersion, code: String): ResolvedVersion {
        return ResolvedVersion(
            id = version.id,
            agentId = version.agentId,
            agentCode = code,
            agentName = code,
            agentDescription = "$code Agent가 요청을 처리합니다.",
            semver = version.semver,
            endpoint = version.endpoint,
            priceAtomic = version.priceAtomic,
            network = version.network,
            asset = version.asset,
            payTo = version.payTo,
        )
    }

    private fun assertUnsafe(action: () -> Unit) {
        assertEquals(
            "AGENT_400_005",
            assertThrows(DomainClientException::class.java, action).errorCode.code
        )
    }

}
