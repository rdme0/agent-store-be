package com.agentstore.dependency

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.model.vo.ResolvedVersion
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuoteSnapshotCompatibilityTest {
    @Test
    fun `legacy snapshot without response format uses JSON`() {
        val snapshot = jacksonObjectMapper().findAndRegisterModules().readValue(
            """
            {
              "version": {
                "id": "00000000-0000-0000-0000-000000000001",
                "agentId": "00000000-0000-0000-0000-000000000002",
                "agentSlug": "legacy",
                "semver": "1.0.0",
                "endpoint": "https://legacy.example.com/invoke",
                "priceAtomic": "1",
                "network": "eip155:84532",
                "asset": "USDC",
                "payTo": "0x0000000000000000000000000000000000000001"
              },
              "dependencies": []
            }
            """.trimIndent(),
            QuoteSnapshotDto::class.java,
        )

        assertEquals(AgentResponseFormat.JSON, snapshot.version.responseFormat)
    }

    @Test
    fun `legacy snapshot without agent description remains readable`() {
        val snapshot = jacksonObjectMapper().findAndRegisterModules().readValue(
            legacySnapshotJson(),
            QuoteSnapshotDto::class.java,
        )

        assertNull(snapshot.version.agentDescription)
    }

    @Test
    fun `snapshot freezes the public agent description`() {
        val snapshot = ResolvedNode(
            version = ResolvedVersion(
                id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                agentId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                agentSlug = "news",
                agentName = "최근 뉴스 확인",
                agentDescription = "시장과 관련된 최신 기사를 살펴봐요.",
                semver = "1.0.0",
                endpoint = "https://news.example.com/invoke",
                priceAtomic = BigInteger.ONE,
                network = "eip155:84532",
                asset = "USDC",
                payTo = "0x0000000000000000000000000000000000000001",
            ),
            dependencies = emptyList(),
        ).snapshot()

        assertEquals("시장과 관련된 최신 기사를 살펴봐요.", snapshot.version.agentDescription)
    }

    @Test
    fun `snapshot serialization excludes payment secrets and invocation token`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val snapshot = mapper.readValue(
            legacySnapshotJson(),
            QuoteSnapshotDto::class.java,
        )

        val serialized = mapper.writeValueAsString(snapshot)
        assertFalse(serialized.contains("privateKey"))
        assertFalse(serialized.contains("paymentSignature"))
        assertFalse(serialized.contains("invocationToken"))
    }

    private fun legacySnapshotJson(): String {
        return """
            {
              "version": {
                "id": "00000000-0000-0000-0000-000000000001",
                "agentId": "00000000-0000-0000-0000-000000000002",
                "agentSlug": "legacy",
                "semver": "1.0.0",
                "endpoint": "https://legacy.example.com/invoke",
                "priceAtomic": "1",
                "network": "eip155:84532",
                "asset": "USDC",
                "payTo": "0x0000000000000000000000000000000000000001"
              },
              "dependencies": []
            }
        """.trimIndent()
    }
}
