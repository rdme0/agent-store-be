package com.agentstore.common.security

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.security.helper.DemoAccessTokenHelper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DemoAccessTokenHelperTest {
    @Test
    fun `issues a six hour domain separated bearer access token`() {
        val now = Instant.parse("2026-09-04T12:00:00Z")
        val helper = DemoAccessTokenHelper(
            properties = properties(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val developerId = UUID.fromString("00000000-0000-0000-0000-00000000d001")

        val issued = helper.issue(developerId)

        assertThat(issued.expiresAt).isEqualTo(now.plus(Duration.ofHours(6)))
        assertThat(helper.authenticate("Bearer ${issued.accessToken}")?.developerId).isEqualTo(developerId)
        val almostExpiredHelper = DemoAccessTokenHelper(
            properties = properties(),
            clock = Clock.fixed(now.plus(Duration.ofHours(6)).minusSeconds(1), ZoneOffset.UTC),
        )
        assertThat(almostExpiredHelper.authenticate("Bearer ${issued.accessToken}")?.developerId).isEqualTo(developerId)
        val expiredHelper = DemoAccessTokenHelper(
            properties = properties(),
            clock = Clock.fixed(now.plus(Duration.ofHours(6)), ZoneOffset.UTC),
        )
        assertThat(expiredHelper.authenticate("Bearer ${issued.accessToken}")).isNull()
        assertThat(helper.authenticate("Bearer ${issued.accessToken}x")).isNull()
        assertThat(helper.authenticate(issued.accessToken)).isNull()
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
            bithumbCacheTtl = Duration.ofMinutes(1),
            bithumbStaleTtl = Duration.ofMinutes(15),
        )
    }
}
