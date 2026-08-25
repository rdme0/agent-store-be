package com.agentstore.execution

import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.agent.repository.AgentCapabilityRepository
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.model.entity.ExecutionQuote
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.request.CreateExecutionRequest
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.guard.ExecutionMutationReadiness
import com.agentstore.execution.model.entity.Execution
import com.agentstore.execution.model.vo.ExecutionStatus
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.execution.repository.ExecutionStepRepository
import com.agentstore.execution.runner.ExecutionRunner
import com.agentstore.execution.service.ExecutionService
import com.agentstore.payment.service.KrwEstimateService
import com.agentstore.payment.service.PaymentService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class ExecutionCapabilitySchemaTest {
    @Test
    fun `invalid root input is rejected before execution persistence`() {
        val objectMapper = jacksonObjectMapper()
        val executionRepository = mock(ExecutionRepository::class.java)
        val quoteService = mock(QuoteService::class.java)
        val capabilityService = AgentCapabilityService(
            mock(AgentCapabilityRepository::class.java),
            mock(AgentVersionRepository::class.java),
            mock(AgentRepository::class.java),
            objectMapper,
        )
        val quoteId = UUID.randomUUID()
        val snapshot = objectMapper.readTree(
            """
            {
              "version": {
                "id": "${UUID.randomUUID()}",
                "functionContract": {"inputSchema":{"type":"object","required":["question"]}}
              },
              "dependencies": []
            }
            """.trimIndent(),
        )
        val quote = ExecutionQuote(quoteId, UUID.randomUUID(), Instant.now().plusSeconds(60), BigInteger.ONE, snapshot)
        `when`(quoteService.requireQuote(quoteId)).thenReturn(quote)
        `when`(quoteService.snapshot(quote)).thenReturn(snapshot)
        val service = ExecutionService(
            executionRepository,
            mock(ExecutionStepRepository::class.java),
            mock(PaymentService::class.java),
            quoteService,
            mock(ExecutionEventService::class.java),
            objectMapper,
            mock(ExecutionRunner::class.java),
            mock(ExecutionMutationReadiness::class.java),
            mock(KrwEstimateService::class.java),
            capabilityService,
        )

        val exception = assertThrows(DomainClientException::class.java) {
            service.create(CreateExecutionRequest(quoteId = quoteId, maxBudgetAtomic = "1"))
        }

        assertEquals(ErrorCode.AGENT_INPUT_SCHEMA_INVALID, exception.errorCode)
        verifyNoInteractions(executionRepository)
    }

    @Test
    fun `execution response uses the persisted provider selection snapshot`() {
        val objectMapper = jacksonObjectMapper().findAndRegisterModules()
        val executionRepository = mock(ExecutionRepository::class.java)
        val stepRepository = mock(ExecutionStepRepository::class.java)
        val quoteService = mock(QuoteService::class.java)
        val executionId = UUID.randomUUID()
        val quoteId = UUID.randomUUID()
        val selectedVersionId = UUID.randomUUID()
        val execution = mock(Execution::class.java)
        `when`(execution.id).thenReturn(executionId)
        `when`(execution.quoteId).thenReturn(quoteId)
        `when`(execution.status).thenReturn(ExecutionStatus.PENDING)
        `when`(execution.maxBudgetAtomic).thenReturn(BigInteger.ONE)
        `when`(execution.reservedCostAtomic).thenReturn(BigInteger.ZERO)
        `when`(execution.actualCostAtomic).thenReturn(BigInteger.ZERO)
        `when`(execution.createdAt).thenReturn(Instant.EPOCH)
        `when`(execution.updatedAt).thenReturn(Instant.EPOCH)
        val snapshot = objectMapper.readTree(
            """
            {
              "version": {
                "id": "${UUID.randomUUID()}",
                "agentId": "${UUID.randomUUID()}",
                "agentCode": "root",
                "semver": "1.0.0",
                "endpoint": "https://root.example.com/invoke",
                "priceAtomic": "1",
                "network": "eip155:84532",
                "asset": "USDC",
                "payTo": "0x0000000000000000000000000000000000000001"
              },
              "dependencies": [{
                "dependencyId": "${UUID.randomUUID()}",
                "versionConstraint": "*",
                "required": true,
                "maxPriceAtomic": "1",
                "maxCalls": 1,
                "selection": {
                  "strategy": "lowest_price",
                  "providerScope": "marketplace",
                  "functionContractId": "${UUID.randomUUID()}",
                  "functionCode": "news-analysis",
                  "functionContractVersion": "1.0.0",
                  "candidates": [],
                  "selectedVersionId": "$selectedVersionId",
                  "selectedReason": "selected_by_lowest_price"
                }
              }]
            }
            """.trimIndent(),
        )
        `when`(executionRepository.findById(executionId)).thenReturn(Optional.of(execution))
        `when`(stepRepository.findAllByExecutionIdOrderByCreatedAtAsc(executionId)).thenReturn(emptyList())
        `when`(quoteService.snapshot(quoteId)).thenReturn(snapshot)
        val service = ExecutionService(
            executionRepository,
            stepRepository,
            mock(PaymentService::class.java),
            quoteService,
            mock(ExecutionEventService::class.java),
            objectMapper,
            mock(ExecutionRunner::class.java),
            mock(ExecutionMutationReadiness::class.java),
            mock(KrwEstimateService::class.java),
            mock(AgentCapabilityService::class.java),
        )

        val response = service.get(executionId)

        assertEquals(selectedVersionId, response.quoteSnapshot.dependencies.single().selection?.selectedVersionId)
        assertEquals("selected_by_lowest_price", response.quoteSnapshot.dependencies.single().selection?.selectedReason)
    }
}
