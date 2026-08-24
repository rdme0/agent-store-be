package com.agentstore.agent

import com.agentstore.agent.dto.request.AgentManifestRequest
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentCapability
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.model.vo.AgentUsageType
import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.agent.service.AgentManifestService
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.dependency.service.DependencyService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AgentManifestServiceTest {
    private val agentService = mock(AgentService::class.java)
    private val functionContractService = mock(AgentCapabilityService::class.java)
    private val dependencyService = mock(DependencyService::class.java)
    private val service = AgentManifestService(
        agentService = agentService,
        functionContractService = functionContractService,
        dependencyService = dependencyService,
    )

    @Test
    fun `valid manifest is canonicalized with a stable hash`() {
        val first = service.validate(request = AgentManifestRequest(content = manifest()))
        val second = service.validate(request = AgentManifestRequest(content = manifest()))

        assertEquals("investment", first.agentCode)
        assertEquals("investment-analysis", first.functionCode)
        assertEquals(first.canonicalContent, second.canonicalContent)
        assertEquals(first.sha256, second.sha256)
    }

    @Test
    fun `unknown manifest field is rejected before import`() {
        val exception = assertThrows(DomainClientException::class.java) {
            service.validate(
                request = AgentManifestRequest(
                    content = manifest().replace("apiVersion: agentstore/v1", "apiVersion: agentstore/v1\nsecret: no"),
                ),
            )
        }

        assertEquals("COMMON_400_001", exception.errorCode.code)
    }

    @Test
    fun `yaml alias is rejected`() {
        val exception = assertThrows(DomainClientException::class.java) {
            service.validate(
                request = AgentManifestRequest(
                    content = """
                        apiVersion: agentstore/v1
                        agent: &agent
                          developerId: 00000000-0000-0000-0000-000000000001
                        duplicate: *agent
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("COMMON_400_001", exception.errorCode.code)
    }

    @Test
    fun `matching draft manifest replaces its dependency declaration without changing version identity`() {
        val developerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val agent = Agent(
            UUID.randomUUID(),
            developerId,
            "investment",
            "투자 분석 Agent",
            "투자 판단을 돕습니다.",
            AgentUsageType.USER_FACING,
        )
        val contract = capability(id = UUID.randomUUID())
        val version = AgentVersion(
            UUID.randomUUID(),
            agent.id,
            contract.id,
            "2.0.0",
            "http://127.0.0.1:8090/agents/investment/invoke",
            BigInteger.valueOf(1_000),
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            AgentResponseFormat.MARKDOWN,
        )
        `when`(agentService.requireVersion(version.id)).thenReturn(version)
        `when`(agentService.requireAgent(agent.id)).thenReturn(agent)
        `when`(
            functionContractService.requireByCode(
                code = "investment-analysis",
                contractVersion = "2.0.0",
            ),
        ).thenReturn(contract)
        `when`(dependencyService.list(sourceVersionId = version.id)).thenReturn(emptyList())

        val result = service.replace(
            versionId = version.id,
            request = AgentManifestRequest(content = manifest()),
        )

        assertEquals(version.id, result.versionId)
        assertEquals("investment", result.content.substringAfter("code: ").substringBefore('\n'))
    }

    private fun manifest(): String {
        return """
            apiVersion: agentstore/v1
            agent:
              developerId: 00000000-0000-0000-0000-000000000001
              code: investment
              name: 투자 분석 Agent
              description: 투자 판단을 돕습니다.
              version: 2.0.0
              usageType: user_facing
              function:
                code: investment-analysis
                version: 2.0.0
              endpoint: http://127.0.0.1:8090/agents/investment/invoke
              payment:
                priceAtomic: "1000"
                network: eip155:84532
                asset: USDC
                payTo: "0x0000000000000000000000000000000000000001"
            dependencies: []
        """.trimIndent()
    }

    private fun capability(id: UUID): AgentCapability {
        val schema = jacksonObjectMapper().readTree("""{"type":"object"}""")
        return AgentCapability(
            id,
            "investment-analysis",
            "2.0.0",
            "투자 분석",
            "투자 분석 기능",
            AgentResponseFormat.MARKDOWN,
            schema,
            schema,
        )
    }
}
