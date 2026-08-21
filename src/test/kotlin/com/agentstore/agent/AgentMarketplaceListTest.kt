package com.agentstore.agent

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.entity.Developer
import com.agentstore.agent.model.vo.AgentListSort
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentDependencyCountProjection
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.resolver.AgentEndpointAddressResolver
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.AgentListCursorCodec
import com.agentstore.agent.service.AgentService
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigInteger
import java.net.InetAddress
import java.time.Instant
import java.util.Optional
import java.util.UUID

class AgentMarketplaceListTest {
    @Test
    fun `keyset cursor continues after the last emitted agent becomes inactive`() {
        val fixture = fixture()
        `when`(
            fixture.agentRepository.findMarketplaceAgentsByCreatedAtDesc(
                "risk", AgentVersionStatus.ACTIVE, false, null, null, PageRequest.of(0, 3),
            )
        ).thenReturn(fixture.newest.take(3))
        `when`(fixture.agentRepository.countDistinctDependenciesByAgentIds(fixture.newest.take(2).map { it.id }))
            .thenReturn(emptyList())

        val firstResult = fixture.service.list(2, null, " risk ", AgentListSort.NEWEST)
        val lastVisible = fixture.newest[1]
        `when`(
            fixture.agentRepository.findMarketplaceAgentsByCreatedAtDesc(
                "risk", AgentVersionStatus.ACTIVE, true, lastVisible.createdAt, lastVisible.id, PageRequest.of(0, 3),
            )
        ).thenReturn(listOf(fixture.newest[2]))
        `when`(fixture.agentRepository.countDistinctDependenciesByAgentIds(listOf(fixture.newest[2].id)))
            .thenReturn(emptyList())

        val secondResult = fixture.service.list(2, firstResult.nextCursor, "risk", AgentListSort.NEWEST)

        assertEquals(listOf("newest-a", "newest-b"), firstResult.items.map { it.slug })
        assertEquals(listOf(AgentVersionStatus.ACTIVE), firstResult.items.first().versions.map { it.status })
        assertEquals(listOf("newest-c"), secondResult.items.map { it.slug })
        assertEquals(null, secondResult.nextCursor)
    }

    @Test
    fun `cursor sort or query mismatch is rejected instead of restarting the listing`() {
        val fixture = fixture()
        `when`(
            fixture.agentRepository.findMarketplaceAgentsByCreatedAtDesc(
                "risk", AgentVersionStatus.ACTIVE, false, null, null, PageRequest.of(0, 2),
            )
        ).thenReturn(fixture.newest.take(2))
        `when`(fixture.agentRepository.countDistinctDependenciesByAgentIds(listOf(fixture.newest[0].id)))
            .thenReturn(emptyList())
        val cursor = fixture.service.list(1, null, "risk", AgentListSort.NEWEST).nextCursor!!

        assertThrows(DomainClientException::class.java) {
            fixture.service.list(1, cursor, "other", AgentListSort.NEWEST)
        }
        assertThrows(DomainClientException::class.java) {
            fixture.service.list(1, cursor, "risk", AgentListSort.NAME_ASC)
        }
    }

    @Test
    fun `tampered cursor is rejected before querying the marketplace`() {
        val fixture = fixture()

        assertThrows(DomainClientException::class.java) {
            fixture.service.list(20, "invalid.cursor", null, AgentListSort.NEWEST)
        }
    }

    @Test
    fun `dependency count is a distinct dependency agent count rather than a version count`() {
        val fixture = fixture()
        val agent = fixture.newest.first()
        val count = mock(AgentDependencyCountProjection::class.java)
        `when`(count.agentId).thenReturn(agent.id)
        `when`(count.dependencyCount).thenReturn(3)
        `when`(
            fixture.agentRepository.findMarketplaceAgentsByNameAsc(
                null, AgentVersionStatus.ACTIVE, false, null, null, PageRequest.of(0, 21),
            )
        ).thenReturn(listOf(agent))
        `when`(fixture.agentRepository.countDistinctDependenciesByAgentIds(listOf(agent.id))).thenReturn(listOf(count))

        val result = fixture.service.list(20, null, null, AgentListSort.NAME_ASC)

        assertEquals(3, result.items.single().dependencyCount)
    }

    @Test
    fun `search query longer than one hundred characters is rejected`() {
        val fixture = fixture()

        assertThrows(DomainClientException::class.java) {
            fixture.service.list(20, null, "x".repeat(101), AgentListSort.NEWEST)
        }
    }

    private fun fixture(): Fixture {
        val agentRepository = mock(AgentRepository::class.java)
        val versionRepository = mock(AgentVersionRepository::class.java)
        val developerRepository = mock(DeveloperRepository::class.java)
        val developerId = UUID.randomUUID()
        val developer = mock(Developer::class.java)
        `when`(developer.displayName).thenReturn("데모 개발자")
        `when`(developerRepository.findById(developerId)).thenReturn(Optional.of(developer))
        val newest = listOf(
            agent(developerId, "newest-a", "Zeta", "2026-08-21T00:00:03Z"),
            agent(developerId, "newest-b", "Beta", "2026-08-21T00:00:02Z"),
            agent(developerId, "newest-c", "Alpha", "2026-08-21T00:00:01Z"),
        )
        newest.forEach { agent ->
            val activeVersion = activeVersion(agent.id)
            `when`(versionRepository.findAllByAgentIdAndStatus(agent.id, AgentVersionStatus.ACTIVE))
                .thenReturn(listOf(activeVersion))
            `when`(versionRepository.findAllByAgentId(agent.id))
                .thenReturn(listOf(activeVersion, draftVersion(agent.id), disabledVersion(agent.id)))
        }
        val policy = AgentEndpointPolicy(
            MockEnvironment().apply { setActiveProfiles("dev") },
            AgentEndpointAddressResolver { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) },
        )
        val cursorCodec = AgentListCursorCodec(
            jacksonObjectMapper().findAndRegisterModules(),
            AgentStoreProperties(
                corsOrigins = listOf("http://localhost:5173"),
                runtimeTokenSecret = "test-cursor-secret",
                databaseUrl = "postgresql://postgres:postgres@localhost:5432/agent_store",
            ),
        )
        return Fixture(
            AgentService(agentRepository, versionRepository, developerRepository, policy, cursorCodec),
            agentRepository,
            newest,
        )
    }

    private fun agent(developerId: UUID, slug: String, name: String, createdAt: String): Agent {
        return Agent(UUID.randomUUID(), developerId, slug, name, "$name 설명").also {
            timestamps(it, createdAt)
        }
    }

    private fun activeVersion(agentId: UUID): AgentVersion {
        return AgentVersion(
            UUID.randomUUID(), agentId, "1.0.0", "http://localhost:8090/invoke", BigInteger.ONE,
            "eip155:84532", "USDC", "0x0000000000000000000000000000000000000001",
        ).also {
            it.publish()
            timestamps(it, "2026-08-21T00:00:00Z")
        }
    }

    private fun draftVersion(agentId: UUID): AgentVersion {
        return AgentVersion(
            UUID.randomUUID(), agentId, "1.1.0", "http://localhost:8090/invoke", BigInteger.ONE,
            "eip155:84532", "USDC", "0x0000000000000000000000000000000000000001",
        ).also {
            timestamps(it, "2026-08-21T00:00:00Z")
        }
    }

    private fun disabledVersion(agentId: UUID): AgentVersion {
        return activeVersion(agentId).also { version ->
            version.disable()
        }
    }

    private fun timestamps(entity: Any, createdAt: String) {
        val instant = Instant.parse(createdAt)
        ReflectionTestUtils.setField(entity, "createdAt", instant)
        ReflectionTestUtils.setField(entity, "updatedAt", instant)
    }

    private data class Fixture(
        val service: AgentService,
        val agentRepository: AgentRepository,
        val newest: List<Agent>,
    )
}
