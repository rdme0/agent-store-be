package com.agentstore.developer

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.security.helper.DemoAccessTokenHelper
import com.agentstore.developer.service.DemoAccessService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DemoAccessServiceTest {
    @Test
    fun `issues shared demo access without a request challenge`() {
        val now = Instant.parse("2026-09-05T00:00:00Z")
        val tokenHelper = DemoAccessTokenHelper(
            properties = properties(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val service = DemoAccessService(tokenHelper = tokenHelper)

        val response = service.issue()

        assertThat(response.accessToken).isNotBlank()
        assertThat(response.expiresAt).isEqualTo(now.plus(Duration.ofDays(365)))
        assertThat(tokenHelper.authenticate("Bearer ${response.accessToken}")).isNotNull()
    }

    private fun properties(): AgentStoreProperties {
        return AgentStoreProperties(
            serviceName = "agent-store-api",
            apiVersion = "0.1.0",
            runtimeCallbackBaseUrl = "http://127.0.0.1:8080",
            demoAgentBaseUrl = "http://127.0.0.1:8090",
            corsOrigins = listOf("http://localhost:5173"),
            runtimeTokenSecret = "demo-access-test-secret",
            bithumbApiUrl = "https://api.bithumb.com",
            bithumbRequestTimeout = Duration.ofSeconds(2),
            bithumbCacheTtl = Duration.ofSeconds(60),
            bithumbStaleTtl = Duration.ofMinutes(15),
        )
    }
}
