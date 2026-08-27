package com.agentstore.dependency

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.FunctionContract
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.service.FunctionContractService
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.model.entity.AgentDependency
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.model.vo.ResolvedGraph
import com.agentstore.dependency.repository.AgentDependencyAllowedProviderRepository
import com.agentstore.dependency.repository.AgentDependencyRepository
import com.agentstore.dependency.resolver.DependencyResolver
import com.agentstore.execution.service.ProviderMetricService
import com.agentstore.execution.service.ProviderPerformanceDto
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DependencyResolverFunctionContractTest {
    @Test
    fun `lowest price and latest version select deterministic function providers`() {
        val fixture = fixture()

        val lowestPrice = fixture.resolve(
            providerScope = ProviderScope.MARKETPLACE,
            strategy = ProviderSelectionStrategy.LOWEST_PRICE,
            metrics = emptyMap(),
            allowedAgentIds = emptySet(),
        )
        val latestVersion = fixture.resolve(
            providerScope = ProviderScope.MARKETPLACE,
            strategy = ProviderSelectionStrategy.LATEST_VERSION,
            metrics = emptyMap(),
            allowedAgentIds = emptySet(),
        )

        assertEquals(fixture.cheap.id, lowestPrice.root.dependencies.single().resolved?.version?.id)
        assertEquals(fixture.newest.id, latestVersion.root.dependencies.single().resolved?.version?.id)
        assertEquals("selected_by_lowest_price", lowestPrice.root.dependencies.single().selection?.selectedReason)
        assertEquals("news-analysis", lowestPrice.root.dependencies.single().selection?.functionCode)
    }

    @Test
    fun `metric strategies reject providers without enough observations`() {
        val fixture = fixture()

        val exception = assertThrows(DomainClientException::class.java) {
            fixture.resolve(
                providerScope = ProviderScope.MARKETPLACE,
                strategy = ProviderSelectionStrategy.HIGHEST_RELIABILITY,
                metrics = emptyMap(),
                allowedAgentIds = emptySet(),
            )
        }

        assertEquals(ErrorCode.PROVIDER_METRICS_INSUFFICIENT, exception.errorCode)

    }

    @Test
    fun `allowlist excludes providers outside the declaration`() {
        val fixture = fixture()

        val resolved = fixture.resolve(
            providerScope = ProviderScope.ALLOWLIST,
            strategy = ProviderSelectionStrategy.LOWEST_PRICE,
            metrics = emptyMap(),
            allowedAgentIds = setOf(fixture.newest.agentId),
        )

        val selection = resolved.root.dependencies.single().selection
        assertEquals(fixture.newest.id, selection?.selectedVersionId)
        assertEquals(1, selection?.candidates?.size)
    }

    @Test
    fun `Python comparator range excludes incompatible function provider versions`() {
        val fixture = fixture()

        val resolved = fixture.resolve(
            providerScope = ProviderScope.MARKETPLACE,
            strategy = ProviderSelectionStrategy.LATEST_VERSION,
            metrics = emptyMap(),
            allowedAgentIds = emptySet(),
            versionConstraint = ">=1.0.0,<2.0.0",
        )

        val selection = resolved.root.dependencies.single().selection
        assertEquals(fixture.cheap.id, selection?.selectedVersionId)
        assertEquals("version_mismatch", selection?.candidates?.single { it.versionId == fixture.newest.id }?.status)
    }

    @Test
    fun `more than fifty marketplace candidates are rejected`() {
        val fixture = fixture()
        val providers = (1..51).map { index ->
            fixture.provider(
                code = "provider-$index",
                semver = "1.0.$index",
                priceAtomic = index.toLong(),
            )
        }
        fixture.setProviders(providers = providers)

        val exception = assertThrows(DomainClientException::class.java) {
            fixture.resolve(
                providerScope = ProviderScope.MARKETPLACE,
                strategy = ProviderSelectionStrategy.LOWEST_PRICE,
                metrics = emptyMap(),
                allowedAgentIds = emptySet(),
            )
        }

        assertEquals(ErrorCode.PROVIDER_CANDIDATE_LIMIT_EXCEEDED, exception.errorCode)
    }

    private fun fixture(): ResolverFixture {
        val agentService = mock(AgentService::class.java)
        val contractService = mock(FunctionContractService::class.java)
        val dependencyRepository = mock(AgentDependencyRepository::class.java)
        val allowedProviderRepository = mock(AgentDependencyAllowedProviderRepository::class.java)
        val metricService = mock(ProviderMetricService::class.java)
        val functionContractId = UUID.randomUUID()
        val rootAgent = agent(code = "root")
        val cheapAgent = agent(code = "news-fast")
        val newestAgent = agent(code = "news-deep")
        val root = version(
            agent = rootAgent,
            functionContractId = null,
            semver = "1.0.0",
            priceAtomic = 1,
        )
        val cheap = version(
            agent = cheapAgent,
            functionContractId = functionContractId,
            semver = "1.1.0",
            priceAtomic = 900,
        )
        val newest = version(
            agent = newestAgent,
            functionContractId = functionContractId,
            semver = "2.0.0",
            priceAtomic = 1_500,
        )
        val schema = jacksonObjectMapper().readTree("""{"type":"object"}""")
        val contract = FunctionContract(
            functionContractId,
            "news-analysis",
            "1.0.0",
            "뉴스 분석",
            "최근 뉴스를 분석합니다.",
            AgentResponseFormat.JSON,
            schema,
            schema,
        )

        `when`(agentService.requireVersion(root.id)).thenReturn(root)
        listOf(rootAgent, cheapAgent, newestAgent).forEach { agent ->
            `when`(agentService.requireAgent(agent.id)).thenReturn(agent)
        }
        `when`(agentService.activeVersionsForFunctionContract(functionContractId)).thenReturn(listOf(newest, cheap))
        `when`(contractService.requireFunctionContract(functionContractId)).thenReturn(contract)
        `when`(dependencyRepository.findAllBySourceVersionIdOrderByIdAsc(cheap.id)).thenReturn(emptyList())
        `when`(dependencyRepository.findAllBySourceVersionIdOrderByIdAsc(newest.id)).thenReturn(emptyList())

        return ResolverFixture(
            resolver = DependencyResolver(
                agentService,
                contractService,
                dependencyRepository,
                allowedProviderRepository,
                metricService,
            ),
            agentService = agentService,
            dependencyRepository = dependencyRepository,
            allowedProviderRepository = allowedProviderRepository,
            metricService = metricService,
            functionContractId = functionContractId,
            root = root,
            cheap = cheap,
            newest = newest,
        )
    }

    private fun agent(code: String): Agent {
        return Agent(UUID.randomUUID(), UUID.randomUUID(), code, code, "$code description")
    }

    private fun version(
        agent: Agent,
        functionContractId: UUID?,
        semver: String,
        priceAtomic: Long,
    ): AgentVersion {
        val version = AgentVersion(
            UUID.randomUUID(),
            agent.id,
            functionContractId,
            semver,
            "https://${agent.code}.example.com/invoke",
            BigInteger.valueOf(priceAtomic),
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            AgentResponseFormat.JSON,
        )
        version.publish()
        return version
    }

    private inner class ResolverFixture(
        val resolver: DependencyResolver,
        val agentService: AgentService,
        val dependencyRepository: AgentDependencyRepository,
        val allowedProviderRepository: AgentDependencyAllowedProviderRepository,
        val metricService: ProviderMetricService,
        val functionContractId: UUID,
        val root: AgentVersion,
        val cheap: AgentVersion,
        val newest: AgentVersion,
    ) {
        fun resolve(
            providerScope: ProviderScope,
            strategy: ProviderSelectionStrategy,
            metrics: Map<UUID, ProviderPerformanceDto>,
            allowedAgentIds: Set<UUID>,
            versionConstraint: String = "*",
        ): ResolvedGraph {
            val dependency = AgentDependency(
                UUID.randomUUID(),
                root.id,
                null,
                versionConstraint,
                true,
                BigInteger.valueOf(2_000),
                1,
            )
            dependency.configureFunctionSelection(
                functionContractId,
                providerScope,
                strategy,
                null,
                null,
            )
            `when`(dependencyRepository.findAllBySourceVersionIdOrderByIdAsc(root.id)).thenReturn(listOf(dependency))
            `when`(
                metricService.performance(
                    functionContractId = functionContractId,
                    versionIds = listOf(newest.id, cheap.id),
                ),
            ).thenReturn(metrics)
            `when`(allowedProviderRepository.findAllByIdDependencyId(dependency.id)).thenReturn(
                allowedAgentIds.map { agentId ->
                    com.agentstore.dependency.model.entity.AgentDependencyAllowedProvider(dependency.id, agentId)
                },
            )
            return resolver.resolve(
                rootVersionId = root.id,
                allowUnresolvedRequired = false,
                allowPriceExceeded = false,
            )
        }

        fun provider(code: String, semver: String, priceAtomic: Long): AgentVersion {
            val provider = agent(code = code)
            val version = version(
                agent = provider,
                functionContractId = functionContractId,
                semver = semver,
                priceAtomic = priceAtomic,
            )
            `when`(agentService.requireAgent(provider.id)).thenReturn(provider)
            `when`(dependencyRepository.findAllBySourceVersionIdOrderByIdAsc(version.id)).thenReturn(emptyList())
            return version
        }

        fun setProviders(providers: List<AgentVersion>) {
            `when`(agentService.activeVersionsForFunctionContract(functionContractId)).thenReturn(providers)
        }
    }
}
