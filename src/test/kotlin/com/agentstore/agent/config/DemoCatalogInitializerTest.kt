package com.agentstore.agent.config

import com.agentstore.agent.dto.request.DemoCatalogDefinition
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.service.DemoCatalogRegistrationService
import com.agentstore.common.config.AgentStoreProperties
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.springframework.boot.DefaultApplicationArguments

class DemoCatalogInitializerTest {
    @Test
    fun `dev initializer registers the complete catalog only for an empty registry`() {
        val repository = mock(AgentRepository::class.java)
        val registeredCatalogs = mutableListOf<DemoCatalogDefinition>()
        val service = mock(
            DemoCatalogRegistrationService::class.java,
            Answer { invocation ->
                registeredCatalogs += invocation.getArgument<DemoCatalogDefinition>(0)
                null
            },
        )
        `when`(repository.count()).thenReturn(0L)

        DemoCatalogInitializer(repository, service, properties()).run(DefaultApplicationArguments())

        assertEquals(1, registeredCatalogs.size)
        val catalog = registeredCatalogs.single()
        assertEquals(13, catalog.agents.size)
        assertEquals(
            setOf("investment-analysis", "shopping-assistant", "travel-planner"),
            catalog.agents.filter { it.dependencies.isNotEmpty() }.map { it.code }.toSet(),
        )
        assertEquals(9, catalog.agents.sumOf { it.dependencies.size })
        assertEquals(
            setOf(">=1.0.0,<2.0.0"),
            catalog.agents.flatMap { it.dependencies }.map { it.versionConstraint }.toSet(),
        )
    }

    @Test
    fun `dev initializer leaves a nonempty registry unchanged`() {
        val repository = mock(AgentRepository::class.java)
        val service = mock(DemoCatalogRegistrationService::class.java)
        `when`(repository.count()).thenReturn(1L)

        DemoCatalogInitializer(repository, service, properties()).run(DefaultApplicationArguments())

        verifyNoInteractions(service)
    }

    private fun properties(): AgentStoreProperties {
        return AgentStoreProperties(
            serviceName = "agent-store-api",
            apiVersion = "0.1.0",
            runtimeCallbackBaseUrl = "http://127.0.0.1:8080",
            demoAgentBaseUrl = "http://127.0.0.1:8090",
            corsOrigins = listOf("http://localhost:5173"),
            runtimeTokenSecret = "test-secret",
            bithumbApiUrl = "https://api.bithumb.com",
            bithumbRequestTimeout = Duration.ofSeconds(2),
            bithumbCacheTtl = Duration.ofMinutes(1),
            bithumbStaleTtl = Duration.ofMinutes(15),
        )
    }
}
