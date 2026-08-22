package com.agentstore.x402.signer

import com.agentstore.x402.dto.internal.X402PaymentRequiredDto
import com.agentstore.x402.service.X402PaymentService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class X402Eip3009SignerTest {
    private companion object {
        const val PRIVATE_KEY = "0x1111111111111111111111111111111111111111111111111111111111111111"
        const val EXPECTED_SIGNATURE =
            "0x0b95344cfc03ac8f0b645e58c81d8163af361222fdf1db40790bba6f6a727e6f76092fc2bbc66192d5f5eb5741d18cc0ce6e287ba02279cd0a01532981f717a51b"
    }

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `EIP-3009 typed data signature matches the official viem shape`() {
        val nonce = HexFormat.of()
            .parseHex("f3746613c2d920b5fdabc0856f2aeb2d4f88ee6037b8cc5d04a71a4462f13480")
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                nonce.copyInto(bytes)
            }
        }
        val selected = objectMapper.createObjectNode().apply {
            put("scheme", "exact")
            put("network", X402PaymentService.BASE_SEPOLIA)
            put("amount", "10000")
            put("asset", X402PaymentService.BASE_SEPOLIA_USDC)
            put("payTo", "0x0000000000000000000000000000000000000001")
            put("maxTimeoutSeconds", 60)
            set<ObjectNode>(
                "extra",
                objectMapper.createObjectNode().apply {
                    put("name", "USDC")
                    put("version", "2")
                })
        }
        val required = X402PaymentRequiredDto(
            objectMapper.createObjectNode().put("url", "https://agent.example.com/invoke"),
            selected,
            60,
            "USDC",
            "2",
            null,
        )
        val signer = X402Eip3009Signer(
            PRIVATE_KEY,
            objectMapper,
            Clock.fixed(Instant.ofEpochSecond(1_740_672_094), ZoneOffset.UTC),
            random,
        )

        val payload = signer.createPaymentPayload(required)

        assertThat(payload.at("/payload/authorization/from").textValue())
            .isEqualTo("0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a")
        assertThat(
            payload.at("/payload/authorization/validBefore").textValue()
        ).isEqualTo("1740672154")
        assertThat(payload.at("/payload/signature").textValue()).isEqualTo(EXPECTED_SIGNATURE)
    }

    @Test
    fun `zero and out of range private keys are rejected`() {
        listOf(
            "0x" + "0".repeat(64),
            "0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
        ).forEach { privateKey ->
            assertThatThrownBy {
                X402Eip3009Signer(
                    privateKey = privateKey,
                    objectMapper = objectMapper,
                    clock = Clock.systemUTC(),
                    secureRandom = SecureRandom(),
                )
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("X402_PRIVATE_KEY is invalid")
        }
    }

}
