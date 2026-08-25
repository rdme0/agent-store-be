package com.agentstore.external

import com.agentstore.agent.service.AgentCapabilityService
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.dependency.dto.internal.ResolvedVersionSnapshotDto
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.model.entity.ExecutionQuote
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.dto.response.ExecutionResponse
import com.agentstore.execution.dto.internal.ExecutionStartDto
import com.agentstore.execution.service.ExecutionService
import com.agentstore.external.config.ExternalApiProperties
import com.agentstore.external.dto.internal.ExternalPaymentRequirementDto
import com.agentstore.external.dto.internal.IncomingPaymentSettlementDto
import com.agentstore.external.dto.internal.IncomingPaymentVerificationDto
import com.agentstore.external.dto.request.CreateExternalInvocationIntentRequest
import com.agentstore.external.exception.ExternalIncomingPaymentRejectedException
import com.agentstore.external.exception.ExternalIncomingPaymentUnknownException
import com.agentstore.external.model.entity.ExternalInvocationIntent
import com.agentstore.external.repository.ExternalApiSaleRepository
import com.agentstore.external.repository.ExternalInvocationIntentRepository
import com.agentstore.external.service.ExternalInvocationService
import com.agentstore.external.service.ExternalX402PaymentService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.any as anyArgumentMatcher
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate

class ExternalInvocationServiceTest {
    @Test
    fun `invalid provider selection is rejected before a quote or payment challenge`() {
        val fixture = fixture()

        val exception = assertThrows(DomainClientException::class.java) {
            fixture.service.createIntent(
                idempotencyKey = "external-invalid-request-0001",
                request = CreateExternalInvocationIntentRequest(
                    agentCode = "weather",
                    functionCode = "weather.forecast-summary",
                    maxTotalAtomic = "1000",
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT_VALUE, exception.errorCode)
        verifyNoInteractions(fixture.quoteService)
        verify(fixture.intentRepository, never()).save(any())
    }

    @Test
    fun `same idempotency key returns the frozen intent without a second quote`() {
        val fixture = fixture()
        val key = "external-idempotent-request-0001"
        val intent = fixture.intent(
            idempotencyKey = key,
            requestHash = fixture.requestHash(
                CreateExternalInvocationIntentRequest(
                    agentCode = "weather",
                    versionConstraint = "*",
                    maxTotalAtomic = "125",
                ),
            ),
        )
        `when`(fixture.intentRepository.findByIdempotencyKey(key)).thenReturn(intent)

        val created = fixture.service.createIntent(
            idempotencyKey = key,
            request = CreateExternalInvocationIntentRequest(
                agentCode = "weather",
                versionConstraint = "*",
                maxTotalAtomic = "125",
            ),
        )

        assertEquals(intent.id, created.response.id)
        assertEquals("125", created.response.totalCostAtomic)
        verifyNoInteractions(fixture.quoteService)
        verify(fixture.intentRepository, never()).save(any())
    }

    @Test
    fun `platform fee is included before a payment challenge is issued`() {
        val fixture = fixture()
        val key = "external-total-cost-request-0001"
        `when`(fixture.intentRepository.findByIdempotencyKey(key)).thenReturn(null)
        `when`(
            fixture.quoteService.create(
                code = "weather",
                request = QuoteRequest(versionConstraint = ">=1.0.0,<2.0.0"),
            ),
        ).thenReturn(fixture.quote())

        val created = fixture.service.createIntent(
            idempotencyKey = key,
            request = CreateExternalInvocationIntentRequest(
                agentCode = "weather",
                versionConstraint = ">=1.0.0,<2.0.0",
                maxTotalAtomic = "125",
            ),
        )

        assertEquals("100", created.response.providerCostAtomic)
        assertEquals("25", created.response.platformFeeAtomic)
        assertEquals("125", created.response.totalCostAtomic)
    }

    @Test
    fun `settled incoming payment creates one internal execution`() {
        val fixture = fixture()
        val id = UUID.randomUUID()
        val intent = fixture.intent(id = id, idempotencyKey = "external-payment-request-0001", requestHash = "request-hash")
        val executionId = UUID.randomUUID()
        val execution = mock(ExecutionResponse::class.java)
        `when`(execution.id).thenReturn(executionId)
        `when`(fixture.intentRepository.findById(id)).thenReturn(Optional.of(intent))
        `when`(fixture.intentRepository.findByIdForUpdate(id)).thenReturn(intent, intent, intent)
        `when`(fixture.quoteService.requireQuote(intent.quoteId)).thenReturn(
            ExecutionQuote(
                intent.quoteId,
                UUID.randomUUID(),
                Instant.parse("2026-08-24T00:05:00Z"),
                BigInteger("100"),
                fixture.objectMapper.createObjectNode(),
            ),
        )
        `when`(fixture.executionService.create(anyArgument<ExecutionStartDto>())).thenReturn(execution)
        `when`(
            fixture.x402PaymentService.verify(
                anyArgument<String>(),
                anyArgument<ExternalPaymentRequirementDto>(),
            ),
        ).thenReturn(
            IncomingPaymentVerificationDto(payer = "0x0000000000000000000000000000000000000004"),
        )
        `when`(
            fixture.x402PaymentService.settle(
                anyArgument<String>(),
                anyArgument<ExternalPaymentRequirementDto>(),
            ),
        ).thenReturn(
            IncomingPaymentSettlementDto(
                payer = "0x0000000000000000000000000000000000000004",
                transactionHash = "0x" + "1".repeat(64),
            ),
        )
        `when`(fixture.x402PaymentService.paymentFingerprint(anyArgument<String>())).thenReturn("payment-fingerprint")
        `when`(fixture.x402PaymentService.paymentResponse(anyArgument<IncomingPaymentSettlementDto>()))
            .thenReturn("payment-response")

        val result = fixture.service.execute(
            id = id,
            receiptToken = "receipt-token",
            signatureHeader = "payment-signature",
        )

        assertEquals(executionId, result.response?.executionId)
        assertEquals("execution_created", result.response?.status?.wireValue)
        verify(fixture.executionService).create(anyArgument<ExecutionStartDto>())
        verify(fixture.saleRepository).save(any())
    }

    @Test
    fun `facilitator verification rejection leaves the intent ready for a valid signature`() {
        val fixture = fixture()
        val id = UUID.randomUUID()
        val intent = fixture.intent(id = id, idempotencyKey = "external-verify-request-0001", requestHash = "request-hash")
        `when`(fixture.intentRepository.findById(id)).thenReturn(Optional.of(intent))
        `when`(
            fixture.x402PaymentService.verify(
                anyArgument<String>(),
                anyArgument<ExternalPaymentRequirementDto>(),
            ),
        ).thenThrow(ExternalIncomingPaymentRejectedException())

        val result = fixture.service.execute(
            id = id,
            receiptToken = "receipt-token",
            signatureHeader = "invalid-payment-signature",
        )

        assertEquals("payment-required", result.paymentRequiredHeader)
        assertEquals("payment_pending", intent.status.wireValue)
        verifyNoInteractions(fixture.executionService)
    }

    @Test
    fun `unknown settlement result never creates an execution`() {
        val fixture = fixture()
        val id = UUID.randomUUID()
        val intent = fixture.intent(id = id, idempotencyKey = "external-unknown-request-0001", requestHash = "request-hash")
        `when`(fixture.intentRepository.findById(id)).thenReturn(Optional.of(intent))
        `when`(fixture.intentRepository.findByIdForUpdate(id)).thenReturn(intent, intent)
        `when`(
            fixture.x402PaymentService.verify(
                anyArgument<String>(),
                anyArgument<ExternalPaymentRequirementDto>(),
            ),
        ).thenReturn(IncomingPaymentVerificationDto(payer = "0x0000000000000000000000000000000000000004"))
        `when`(
            fixture.x402PaymentService.settle(
                anyArgument<String>(),
                anyArgument<ExternalPaymentRequirementDto>(),
            ),
        ).thenThrow(ExternalIncomingPaymentUnknownException())
        `when`(fixture.x402PaymentService.paymentFingerprint(anyArgument<String>())).thenReturn("payment-fingerprint")

        val exception = assertThrows(DomainClientException::class.java) {
            fixture.service.execute(
                id = id,
                receiptToken = "receipt-token",
                signatureHeader = "payment-signature",
            )
        }

        assertEquals(ErrorCode.EXTERNAL_PAYMENT_RECONCILIATION_REQUIRED, exception.errorCode)
        assertEquals("reconciliation_required", intent.status.wireValue)
        verifyNoInteractions(fixture.executionService)
    }

    @Test
    fun `execution creation failure preserves the settled external sale`() {
        val fixture = fixture()
        val id = UUID.randomUUID()
        val intent = fixture.intent(id = id, idempotencyKey = "external-failure-request-0001", requestHash = "request-hash")
        `when`(fixture.intentRepository.findById(id)).thenReturn(Optional.of(intent))
        `when`(fixture.intentRepository.findByIdForUpdate(id)).thenReturn(intent, intent, intent)
        `when`(fixture.quoteService.requireQuote(intent.quoteId)).thenReturn(executionQuote(intent.quoteId, fixture.objectMapper))
        `when`(fixture.executionService.create(anyArgument<ExecutionStartDto>())).thenThrow(
            DomainClientException(ErrorCode.DEPENDENCY_INVOCATION_FAILED),
        )
        `when`(
            fixture.x402PaymentService.verify(
                anyArgument<String>(),
                anyArgument<ExternalPaymentRequirementDto>(),
            ),
        ).thenReturn(IncomingPaymentVerificationDto(payer = "0x0000000000000000000000000000000000000004"))
        `when`(
            fixture.x402PaymentService.settle(
                anyArgument<String>(),
                anyArgument<ExternalPaymentRequirementDto>(),
            ),
        ).thenReturn(
            IncomingPaymentSettlementDto(
                payer = "0x0000000000000000000000000000000000000004",
                transactionHash = "0x" + "2".repeat(64),
            ),
        )
        `when`(fixture.x402PaymentService.paymentFingerprint(anyArgument<String>())).thenReturn("payment-fingerprint")

        assertThrows(DomainClientException::class.java) {
            fixture.service.execute(
                id = id,
                receiptToken = "receipt-token",
                signatureHeader = "payment-signature",
            )
        }

        assertEquals("settled", intent.status.wireValue)
        verify(fixture.saleRepository).save(any())
    }

    @Test
    fun `missing receipt token does not disclose an external invocation`() {
        val fixture = fixture()
        val id = UUID.randomUUID()
        val intent = fixture.intent(id = id, idempotencyKey = "external-receipt-request-0001", requestHash = "request-hash")
        `when`(fixture.intentRepository.findById(id)).thenReturn(Optional.of(intent))

        val exception = assertThrows(DomainClientException::class.java) {
            fixture.service.get(id = id, receiptToken = null)
        }

        assertEquals(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND, exception.errorCode)
        verifyNoInteractions(fixture.executionService)
    }

    private fun fixture(): Fixture {
        val objectMapper = jacksonObjectMapper()
        val properties = ExternalApiProperties(
            publicBaseUrl = "https://api.example.com",
            payTo = "0x0000000000000000000000000000000000000002",
            facilitatorUrl = "https://facilitator.example.com",
            facilitatorRequestTimeout = Duration.ofSeconds(5),
            authorizationTimeout = Duration.ofSeconds(60),
            feeBasisPoints = 2500,
            intentTtl = Duration.ofMinutes(5),
            receiptTtl = Duration.ofMinutes(15),
            rateLimitPerMinute = 30,
        )
        val intentRepository = mock(ExternalInvocationIntentRepository::class.java)
        val quoteService = mock(QuoteService::class.java)
        val executionService = mock(ExecutionService::class.java)
        val saleRepository = mock(ExternalApiSaleRepository::class.java)
        val x402PaymentService = mock(ExternalX402PaymentService::class.java)
        `when`(x402PaymentService.paymentRequired(anyArgument())).thenReturn(paymentRequirement(objectMapper, properties))
        val service = ExternalInvocationService(
            properties = properties,
            agentStoreProperties = AgentStoreProperties(
                serviceName = "agent-store-api",
                apiVersion = "0.1.0",
                runtimeCallbackBaseUrl = "http://127.0.0.1:8080",
                corsOrigins = listOf("http://localhost:*"),
                runtimeTokenSecret = "external-invocation-test-secret",
                paymentMode = "simulated",
                bithumbApiUrl = "https://api.bithumb.com",
                bithumbRequestTimeout = Duration.ofSeconds(2),
                bithumbCacheTtl = Duration.ofMinutes(1),
                bithumbStaleTtl = Duration.ofMinutes(15),
            ),
            intentRepository = intentRepository,
            saleRepository = saleRepository,
            quoteService = quoteService,
            executionService = executionService,
            capabilityService = mock(AgentCapabilityService::class.java),
            x402PaymentService = x402PaymentService,
            objectMapper = objectMapper,
            transactionTemplate = transactionTemplate(),
            clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC),
        )
        return Fixture(
            service = service,
            intentRepository = intentRepository,
            saleRepository = saleRepository,
            quoteService = quoteService,
            executionService = executionService,
            x402PaymentService = x402PaymentService,
            objectMapper = objectMapper,
        )
    }

    private fun paymentRequirement(
        objectMapper: ObjectMapper,
        properties: ExternalApiProperties,
    ): ExternalPaymentRequirementDto {
        val requirement = objectMapper.createObjectNode()
        return ExternalPaymentRequirementDto(
            resourceUrl = "https://api.example.com/v1/invocation-intents/test/execute",
            amountAtomic = "125",
            payTo = properties.payTo,
            header = "payment-required",
            requirement = requirement,
            expiresAt = Instant.parse("2026-08-24T00:05:00Z"),
        )
    }

    private fun executionQuote(id: UUID, objectMapper: ObjectMapper): ExecutionQuote {
        return ExecutionQuote(
            id,
            UUID.randomUUID(),
            Instant.parse("2026-08-24T00:05:00Z"),
            BigInteger("100"),
            objectMapper.createObjectNode(),
        )
    }

    private fun transactionTemplate(): TransactionTemplate {
        val template = mock(TransactionTemplate::class.java)
        doAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any?>>(0)
            callback.doInTransaction(mock(TransactionStatus::class.java))
        }.`when`(template).execute<Any?>(any())
        return template
    }

    private data class Fixture(
        val service: ExternalInvocationService,
        val intentRepository: ExternalInvocationIntentRepository,
        val saleRepository: ExternalApiSaleRepository,
        val quoteService: QuoteService,
        val executionService: ExecutionService,
        val x402PaymentService: ExternalX402PaymentService,
        val objectMapper: ObjectMapper,
    ) {
        fun quote(): QuoteResponse {
            return QuoteResponse(
                id = UUID.randomUUID(),
                rootVersionId = UUID.randomUUID(),
                expiresAt = Instant.parse("2026-08-24T00:05:00Z"),
                maxCostAtomic = "100",
                snapshot = QuoteSnapshotDto(
                    version = ResolvedVersionSnapshotDto(
                        id = UUID.randomUUID(),
                        agentId = UUID.randomUUID(),
                        agentCode = "weather",
                        semver = "1.0.0",
                        endpoint = "https://weather.example.com/invoke",
                        priceAtomic = "100",
                        network = "eip155:84532",
                        asset = "USDC",
                        payTo = "0x0000000000000000000000000000000000000003",
                    ),
                    dependencies = emptyList(),
                ),
                warnings = emptyList(),
            )
        }

        fun intent(
            id: UUID = UUID.randomUUID(),
            idempotencyKey: String,
            requestHash: String,
        ): ExternalInvocationIntent {
            return ExternalInvocationIntent(
                id,
                UUID.randomUUID(),
                idempotencyKey,
                requestHash,
                requestHash("receipt-token"),
                Instant.parse("2026-08-24T00:15:00Z"),
                BigInteger("100"),
                BigInteger("25"),
                BigInteger("125"),
                "0x0000000000000000000000000000000000000002",
                null,
                null,
                Instant.parse("2026-08-24T00:05:00Z"),
            )
        }

        fun requestHash(request: CreateExternalInvocationIntentRequest): String {
            val bytes = objectMapper.writeValueAsBytes(request)
            return requestHash(bytes)
        }

        private fun requestHash(value: String): String {
            return requestHash(value.toByteArray())
        }

        private fun requestHash(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return HexFormat.of().formatHex(digest)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArgument(): T {
        anyArgumentMatcher<T>()
        return null as T
    }
}
