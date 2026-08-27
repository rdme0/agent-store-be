package com.agentstore.execution

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.execution.model.entity.AgentInvocationObservation
import com.agentstore.execution.model.vo.AgentInvocationOutcome
import com.agentstore.execution.repository.AgentInvocationObservationRepository
import com.agentstore.execution.service.ProviderMetricService
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ProviderMetricServiceTest {
    @Test
    fun `payment uncertainty is excluded while invalid output lowers provider reliability`() {
        val repository = mock(AgentInvocationObservationRepository::class.java)
        val contractId = UUID.randomUUID()
        val now = Instant.parse("2026-08-24T00:00:00Z")
        val agent = Agent(UUID.randomUUID(), UUID.randomUUID(), "news", "뉴스", "뉴스")
        val version = AgentVersion(
            UUID.randomUUID(),
            agent.id,
            contractId,
            "1.0.0",
            "http://127.0.0.1:8090/agents/news/invoke",
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            AgentResponseFormat.JSON,
        ).also(AgentVersion::publish)
        val observations = (1..20).map { index ->
            observation(
                version = version,
                contractId = contractId,
                now = now,
                outcome = if (index == 20) {
                    AgentInvocationOutcome.OUTPUT_SCHEMA_INVALID
                } else {
                    AgentInvocationOutcome.SUCCESS
                },
            )
        } + observation(
            version = version,
            contractId = contractId,
            now = now,
            outcome = AgentInvocationOutcome.PAYMENT_RECONCILIATION_REQUIRED,
        )
        `when`(
            repository.findAllByFunctionContractIdAndCompletedAtGreaterThanEqual(
                contractId,
                now.minus(Duration.ofDays(30)),
            ),
        ).thenReturn(observations)
        val service = ProviderMetricService(
            observationRepository = repository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val performance = service.performance(
            functionContractId = contractId,
            versionIds = listOf(version.id),
        ).getValue(version.id)

        assertEquals(20, performance.observationCount)
        assertEquals(95, performance.contractCompliancePercent)
        assertEquals(true, performance.isMature)
    }

    private fun observation(
        version: AgentVersion,
        contractId: UUID,
        now: Instant,
        outcome: AgentInvocationOutcome,
    ): AgentInvocationObservation {
        return AgentInvocationObservation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            version.id,
            contractId,
            now.minusSeconds(1),
        ).also { observation -> observation.finish(outcome, now) }
    }
}
