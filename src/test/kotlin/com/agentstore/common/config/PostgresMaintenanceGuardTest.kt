package com.agentstore.common.config

import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class PostgresMaintenanceGuardTest {
    private val integrationEnvironment = MockEnvironment()

    @Test
    fun `fails closed before datasource when maintenance flag is absent`() {
        val guard = PostgresMaintenanceGuard { false }

        assertThatIllegalArgumentException().isThrownBy {
            guard.verify(integrationEnvironment, properties(integration = true, maintenance = false))
        }.withMessageContaining("SPRING_EXCLUSIVE_MAINTENANCE")
    }

    @Test
    fun `fails closed before datasource when local typescript port is open`() {
        val guard = PostgresMaintenanceGuard { port -> port == 8080 }

        assertThatIllegalStateException().isThrownBy {
            guard.verify(integrationEnvironment, properties(integration = true, maintenance = true))
        }.withMessageContaining("port 8080")
    }

    @Test
    fun `allows exclusive maintenance only when no typescript listener exists`() {
        PostgresMaintenanceGuard { false }.verify(
            integrationEnvironment,
            properties(integration = true, maintenance = true)
        )
    }

    private fun properties(integration: Boolean, maintenance: Boolean): AgentStoreProperties {
        return AgentStoreProperties(
            corsOrigin = "http://localhost:5173",
            runtimeTokenSecret = "test-secret",
            databaseUrl = "postgresql://postgres:postgres@localhost:5432/agent_store?schema=public",
            integrationTestsEnabled = integration,
            exclusiveMaintenanceEnabled = maintenance,
        )
    }
}
