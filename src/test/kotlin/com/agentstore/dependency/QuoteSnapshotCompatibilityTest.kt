package com.agentstore.dependency

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.AgentService
import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.dependency.model.entity.ExecutionQuote
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.model.vo.ResolvedVersion
import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.dependency.resolver.CostResolver
import com.agentstore.dependency.resolver.DependencyResolver
import com.agentstore.dependency.service.QuoteService
import com.agentstore.payment.service.KrwEstimateService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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
                agentCode = "news",
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
        assertEquals("legacy", snapshot.version.agentCode)
        assertFalse(serialized.contains("agentSlug"))
        assertFalse(serialized.contains("targetAgentSlug"))
        assertTrue(serialized.contains("agentCode"))
        assertFalse(serialized.contains("privateKey"))
        assertFalse(serialized.contains("paymentSignature"))
        assertFalse(serialized.contains("invocationToken"))
    }

    @Test
    fun `legacy dependency and provider code fields remain readable`() {
        val snapshot = jacksonObjectMapper().findAndRegisterModules().readValue(
            """
            {
              "version": {
                "id": "00000000-0000-0000-0000-000000000001",
                "agentId": "00000000-0000-0000-0000-000000000002",
                "agentSlug": "root",
                "semver": "1.0.0", "endpoint": "https://example.com/root", "priceAtomic": "1",
                "network": "eip155:84532", "asset": "USDC", "payTo": "0x0000000000000000000000000000000000000001"
              },
              "dependencies": [{
                "dependencyId": "00000000-0000-0000-0000-000000000003",
                "targetAgentSlug": "legacy-target", "versionConstraint": "^1.0.0", "required": true,
                "maxPriceAtomic": "1", "maxCalls": 1,
                "selection": {
                  "functionContractId": "00000000-0000-0000-0000-000000000004",
                  "functionCode": "legacy-function", "functionContractVersion": "1.0.0",
                  "candidates": [{
                    "agentId": "00000000-0000-0000-0000-000000000005", "agentSlug": "legacy-provider",
                    "versionId": "00000000-0000-0000-0000-000000000006", "semver": "1.0.0",
                    "priceAtomic": "1", "status": "selected"
                  }]
                }
              }]
            }
            """.trimIndent(),
            QuoteSnapshotDto::class.java,
        )

        assertEquals("legacy-target", snapshot.dependencies.single().targetAgentCode)
        assertEquals("legacy-provider", snapshot.dependencies.single().selection?.candidates?.single()?.agentCode)
    }

    @Test
    fun `runtime snapshot canonicalizes legacy code fields before raw execution reads`() {
        val objectMapper = jacksonObjectMapper().findAndRegisterModules()
        val quoteId = UUID.randomUUID()
        val quoteRepository = mock(ExecutionQuoteRepository::class.java)
        val quote = ExecutionQuote(
            quoteId,
            UUID.randomUUID(),
            Instant.now().plusSeconds(300),
            BigInteger.ONE,
            objectMapper.readTree(
                """
                {
                  "version": {"agentSlug": "root"},
                  "dependencies": [{
                    "targetAgentSlug": "news",
                    "selection": {"candidates": [{"agentSlug": "news-fast"}]},
                    "resolved": {"version": {"agentSlug": "news-fast"}, "dependencies": []}
                  }]
                }
                """.trimIndent(),
            ),
        )
        `when`(quoteRepository.findById(quoteId)).thenReturn(Optional.of(quote))
        val service = QuoteService(
            agentService = mock(AgentService::class.java),
            quoteRepository = quoteRepository,
            resolver = mock(DependencyResolver::class.java),
            costResolver = mock(CostResolver::class.java),
            endpointPolicy = mock(AgentEndpointPolicy::class.java),
            objectMapper = objectMapper,
            krwEstimateService = mock(KrwEstimateService::class.java),
        )

        val snapshot = service.snapshot(quoteId)

        assertEquals("root", snapshot.path("version").path("agentCode").asText())
        assertEquals("news", snapshot.path("dependencies").first().path("targetAgentCode").asText())
        assertEquals(
            "news-fast",
            snapshot.path("dependencies").first().path("selection").path("candidates").first().path("agentCode").asText(),
        )
        assertEquals(
            "news-fast",
            snapshot.path("dependencies").first().path("resolved").path("version").path("agentCode").asText(),
        )
        assertFalse(snapshot.toString().contains("agentSlug"))
        assertFalse(snapshot.toString().contains("targetAgentSlug"))
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
