package com.agentstore.common.config

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JacksonConfigurationTest {
    private val objectMapper = JacksonConfiguration().objectMapper()

    @Test
    fun `serializes instant response fields as ISO 8601 strings`() {
        val expiresAt = Instant.parse("2026-09-04T07:10:16.571Z")

        val payload = objectMapper.readTree(objectMapper.writeValueAsString(mapOf("expiresAt" to expiresAt)))

        assertThat(payload.path("expiresAt").isTextual).isTrue()
        assertThat(payload.path("expiresAt").asText()).isEqualTo("2026-09-04T07:10:16.571Z")
    }
}
