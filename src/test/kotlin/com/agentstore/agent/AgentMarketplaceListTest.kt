package com.agentstore.agent

import com.agentstore.agent.codec.AgentListCursorCodec
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.entity.Developer
import com.agentstore.agent.model.entity.User
import com.agentstore.agent.model.vo.AgentListSort
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentDependencyCountProjection
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.resolver.AgentEndpointAddressResolver
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.AgentService
import com.agentstore.agent.service.FunctionContractReader
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.support.ExplicitProxy
import com.agentstore.support.emptyReadinessRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.util.ReflectionTestUtils

class AgentMarketplaceListTest {
    @Test
    fun `keyset cursor continues after the last emitted agent becomes inactive`() {
        val fixture = fixture()

        val firstResult = fixture.service.list(
            limit = 2,
            cursor = null,
            query = " risk ",
            sort = AgentListSort.NEWEST,
            usageType = null,
        )
        val secondResult = fixture.service.list(
            limit = 2,
            cursor = firstResult.nextCursor,
            query = "risk",
            sort = AgentListSort.NEWEST,
            usageType = null,
        )

        assertEquals(listOf("newest-a", "newest-b"), firstResult.items.map { item -> item.code })
        assertEquals(listOf(AgentVersionStatus.ACTIVE), firstResult.items.first().versions.map { version -> version.status })
        assertEquals(listOf("newest-c"), secondResult.items.map { item -> item.code })
        assertEquals(null, secondResult.nextCursor)
    }

    @Test
    fun `cursor sort or query mismatch is rejected instead of restarting the listing`() {
        val fixture = fixture()
        val cursor = fixture.service.list(
            limit = 1,
            cursor = null,
            query = "risk",
            sort = AgentListSort.NEWEST,
            usageType = null,
        ).nextCursor!!

        assertThrows(DomainClientException::class.java) {
            fixture.service.list(
                limit = 1,
                cursor = cursor,
                query = "other",
                sort = AgentListSort.NEWEST,
                usageType = null,
            )
        }
        assertThrows(DomainClientException::class.java) {
            fixture.service.list(
                limit = 1,
                cursor = cursor,
                query = "risk",
                sort = AgentListSort.NAME_ASC,
                usageType = null,
            )
        }
    }

    @Test
    fun `tampered cursor is rejected before querying the marketplace`() {
        val fixture = fixture()

        assertThrows(DomainClientException::class.java) {
            fixture.service.list(
                limit = 20,
                cursor = "invalid.cursor",
                query = null,
                sort = AgentListSort.NEWEST,
                usageType = null,
            )
        }
    }

    @Test
    fun `dependency count is a distinct dependency agent count rather than a version count`() {
        val fixture = fixture()
        val result = fixture.service.list(
            limit = 20,
            cursor = null,
            query = null,
            sort = AgentListSort.NAME_ASC,
            usageType = null,
        )

        assertEquals(3, result.items.single().dependencyCount)
    }

    @Test
    fun `search query longer than one hundred characters is rejected`() {
        val fixture = fixture()

        assertThrows(DomainClientException::class.java) {
            fixture.service.list(
                limit = 20,
                cursor = null,
                query = "x".repeat(101),
                sort = AgentListSort.NEWEST,
                usageType = null,
            )
        }
    }

    private fun fixture(): Fixture {
        val developerId = UUID.randomUUID()
        val newest = listOf(
            agent(developerId = developerId, code = "newest-a", name = "Zeta", createdAt = "2026-08-21T00:00:03Z"),
            agent(developerId = developerId, code = "newest-b", name = "Beta", createdAt = "2026-08-21T00:00:02Z"),
            agent(developerId = developerId, code = "newest-c", name = "Alpha", createdAt = "2026-08-21T00:00:01Z"),
        )
        val activeVersions = newest.associate { value -> value.id to activeVersion(agentId = value.id) }
        val allVersions = newest.associate { value ->
            value.id to listOf(
                activeVersions.getValue(value.id),
                draftVersion(agentId = value.id),
                disabledVersion(agentId = value.id),
            )
        }
        val agentRepository = ExplicitProxy(AgentRepository::class.java).apply {
            answer(methodName = "findMarketplaceAgentsByCreatedAtDesc") { arguments ->
                if (arguments?.get(3) == false) newest.take(3) else listOf(newest[2])
            }
            answer(methodName = "findMarketplaceAgentsByNameAsc") { listOf(newest.first()) }
            answer(methodName = "countDistinctDependenciesByAgentIds") { arguments ->
                val ids = arguments?.firstOrNull() as? Collection<*>
                if (ids?.contains(newest.first().id) == true && ids.size == 1) {
                    listOf(DependencyCount(agentId = newest.first().id, dependencyCount = 3))
                } else {
                    emptyList<AgentDependencyCountProjection>()
                }
            }
        }
        val versionRepository = ExplicitProxy(AgentVersionRepository::class.java).apply {
            answer(methodName = "findAllReadyByAgentId") { arguments ->
                activeVersions[arguments?.first() as UUID]?.let(::listOf) ?: emptyList<AgentVersion>()
            }
            answer(methodName = "findAllByAgentId") { arguments ->
                allVersions[arguments?.first() as UUID] ?: emptyList<AgentVersion>()
            }
        }
        val developerRepository = ExplicitProxy(DeveloperRepository::class.java).apply {
            answer(methodName = "findById") { Optional.of(Developer(developerId, User(UUID.randomUUID(), "demo-user"), "데모 개발자")) }
        }
        val functionContractReader = ExplicitProxy(FunctionContractReader::class.java)
        val service = AgentService(
            agentRepository = agentRepository.value,
            agentVersionRepository = versionRepository.value,
            developerRepository = developerRepository.value,
            endpointPolicy = endpointPolicy(),
            cursorCodec = cursorCodec(),
            functionContractService = functionContractReader.value,
            readinessRepository = emptyReadinessRepository(),
        )
        return Fixture(service = service)
    }

    private fun endpointPolicy(): AgentEndpointPolicy {
        return AgentEndpointPolicy(
            environment = MockEnvironment().apply { setActiveProfiles("dev") },
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

    private fun agent(developerId: UUID, code: String, name: String, createdAt: String): Agent {
        return Agent(
            UUID.randomUUID(),
            developerId,
            code,
            name,
            "$name 설명",
        ).also { value -> timestamps(entity = value, createdAt = createdAt) }
    }

    private fun activeVersion(agentId: UUID): AgentVersion {
        return AgentVersion(
            UUID.randomUUID(),
            agentId,
            "1.0.0",
            "http://localhost:8090/invoke",
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
        ).also { value ->
            value.publish()
            timestamps(entity = value, createdAt = "2026-08-21T00:00:00Z")
        }
    }

    private fun draftVersion(agentId: UUID): AgentVersion {
        return AgentVersion(
            UUID.randomUUID(),
            agentId,
            "1.1.0",
            "http://localhost:8090/invoke",
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
        ).also { value -> timestamps(entity = value, createdAt = "2026-08-21T00:00:00Z") }
    }

    private fun disabledVersion(agentId: UUID): AgentVersion {
        return activeVersion(agentId).also { value -> value.disable() }
    }

    private fun timestamps(entity: Any, createdAt: String) {
        val instant = Instant.parse(createdAt)
        ReflectionTestUtils.setField(entity, "createdAt", instant)
        ReflectionTestUtils.setField(entity, "updatedAt", instant)
    }

    private data class DependencyCount(
        override val agentId: UUID,
        override val dependencyCount: Long,
    ) : AgentDependencyCountProjection

    private data class Fixture(
        val service: AgentService,
    )
}
