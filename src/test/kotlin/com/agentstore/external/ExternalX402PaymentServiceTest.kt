package com.agentstore.external

import com.agentstore.external.client.FacilitatorIncomingPaymentClient
import com.agentstore.external.config.ExternalApiProperties
import com.agentstore.external.dto.internal.ExternalPaymentRequirementDto
import com.agentstore.external.exception.ExternalIncomingPaymentRejectedException
import com.agentstore.external.model.entity.ExternalInvocationIntent
import com.agentstore.external.model.vo.ExternalInvocationStatus
import com.agentstore.external.service.ExternalX402PaymentService
import com.agentstore.x402.codec.X402HeaderCodec
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class ExternalX402PaymentServiceTest {
    @Test
    fun `external invocation statuses use lowercase snake case on the wire`() {
        val objectMapper = jacksonObjectMapper()

        val serialized = objectMapper.writeValueAsString(ExternalInvocationStatus.RECONCILIATION_REQUIRED)

        assertEquals("\"reconciliation_required\"", serialized)
    }

    @Test
    fun `payment challenge fixes exact Base Sepolia USDC terms`() {
        val objectMapper = jacksonObjectMapper()
        val service = ExternalX402PaymentService(
            properties = properties(),
            facilitatorClient = mock(FacilitatorIncomingPaymentClient::class.java),
            objectMapper = objectMapper,
        )

        val requirement = service.paymentRequired(intent = intent())
        val challenge = X402HeaderCodec(objectMapper).decodeObject(value = requirement.header)
        val accepted = challenge.path("accepts").single()

        assertEquals(2, challenge.path("x402Version").asInt())
        assertEquals("exact", accepted.path("scheme").asText())
        assertEquals("eip155:84532", accepted.path("network").asText())
        assertEquals("0x036CbD53842c5426634e7929541eC2318f3dCF7e", accepted.path("asset").asText())
        assertEquals("eip3009", accepted.path("extra").path("assetTransferMethod").asText())
        assertEquals("1250", accepted.path("amount").asText())
    }

    @Test
    fun `mismatched payment payload is rejected before facilitator verification`() {
        val objectMapper = jacksonObjectMapper()
        val facilitator = mock(FacilitatorIncomingPaymentClient::class.java)
        val service = ExternalX402PaymentService(
            properties = properties(),
            facilitatorClient = facilitator,
            objectMapper = objectMapper,
        )
        val requirement = service.paymentRequired(intent = intent())
        val payload = validPaymentPayload(objectMapper = objectMapper, requirement = requirement)
        val accepted = payload.path("accepted") as ObjectNode
        accepted.put("network", "eip155:1")
        val signature = X402HeaderCodec(objectMapper).encode(value = payload)

        assertThrows(ExternalIncomingPaymentRejectedException::class.java) {
            service.verify(signatureHeader = signature, requirement = requirement)
        }

        verifyNoInteractions(facilitator)
    }

    @Test
    fun `authorization cannot outlive the frozen invocation intent`() {
        val objectMapper = jacksonObjectMapper()
        val facilitator = mock(FacilitatorIncomingPaymentClient::class.java)
        val service = ExternalX402PaymentService(
            properties = properties(),
            facilitatorClient = facilitator,
            objectMapper = objectMapper,
        )
        val requirement = service.paymentRequired(intent = intent())
        val payload = validPaymentPayload(objectMapper = objectMapper, requirement = requirement)
        val authorization = payload.path("payload").path("authorization") as ObjectNode
        authorization.put("validBefore", requirement.expiresAt.plusSeconds(1).epochSecond)
        val signature = X402HeaderCodec(objectMapper).encode(value = payload)

        assertThrows(ExternalIncomingPaymentRejectedException::class.java) {
            service.verify(signatureHeader = signature, requirement = requirement)
        }

        verifyNoInteractions(facilitator)
    }

    private fun validPaymentPayload(
        objectMapper: ObjectMapper,
        requirement: ExternalPaymentRequirementDto,
    ): ObjectNode {
        val root = objectMapper.createObjectNode()
        root.put("x402Version", 2)
        root.set<ObjectNode>("resource", objectMapper.createObjectNode().put("url", requirement.resourceUrl))
        root.set<ObjectNode>("accepted", requirement.requirement.deepCopy())
        root.set<ObjectNode>("payload", objectMapper.createObjectNode().apply {
            put("signature", "0x" + "1".repeat(130))
            set<ObjectNode>("authorization", objectMapper.createObjectNode().apply {
                put("from", "0x0000000000000000000000000000000000000001")
                put("to", requirement.payTo)
                put("value", requirement.amountAtomic)
                put("validBefore", requirement.expiresAt.epochSecond)
            })
        })
        return root
    }

    private fun intent(): ExternalInvocationIntent {
        val now = Instant.parse("2026-08-24T00:00:00Z")
        return ExternalInvocationIntent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "external-idempotency-key-0001",
            "request-hash",
            "receipt-token-hash",
            now.plusSeconds(600),
            BigInteger("1000"),
            BigInteger("250"),
            BigInteger("1250"),
            "0x0000000000000000000000000000000000000002",
            "Question",
            null,
            now.plusSeconds(300),
        )
    }

    private fun properties(): ExternalApiProperties {
        return ExternalApiProperties(
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
    }
}
