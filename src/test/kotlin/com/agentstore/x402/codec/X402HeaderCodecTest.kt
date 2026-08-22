package com.agentstore.x402.codec

import com.agentstore.x402.service.X402PaymentService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class X402HeaderCodecTest {
    private companion object {
        const val PAYMENT_REQUIRED_VECTOR =
            "eyJ4NDAyVmVyc2lvbiI6MiwicmVzb3VyY2UiOnsidXJsIjoiaHR0cHM6Ly9hZ2VudC5leGFtcGxlL2ludm9rZSJ9LCJhY2NlcHRzIjpbXX0="
        const val PAYMENT_RESPONSE_VECTOR =
            "eyJzdWNjZXNzIjp0cnVlLCJ0cmFuc2FjdGlvbiI6IjB4YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYSIsIm5ldHdvcmsiOiJlaXAxNTU6ODQ1MzIifQ=="
        const val TRANSACTION = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }

    private val objectMapper = jacksonObjectMapper()
    private val codec = X402HeaderCodec(objectMapper)

    @Test
    fun `v2 header matches the fixed standard base64 JSON wire vector`() {
        val decoded = codec.decodeObject(PAYMENT_REQUIRED_VECTOR)

        assertThat(decoded.path("x402Version").intValue()).isEqualTo(2)
        assertThat(
            decoded.at("/resource/url").textValue()
        ).isEqualTo("https://agent.example/invoke")
        assertThat(codec.encode(decoded)).isEqualTo(PAYMENT_REQUIRED_VECTOR)
    }

    @Test
    fun `settlement receipt decodes from a fixed v2 wire vector`() {
        val receipt = codec.decodeReceipt(PAYMENT_RESPONSE_VECTOR)

        assertThat(receipt.success).isTrue()
        assertThat(receipt.transaction).isEqualTo(TRANSACTION)
        assertThat(receipt.network).isEqualTo(X402PaymentService.BASE_SEPOLIA)
    }

}
