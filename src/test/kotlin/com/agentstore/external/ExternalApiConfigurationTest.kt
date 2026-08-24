package com.agentstore.external

import com.agentstore.external.client.FacilitatorIncomingPaymentClient
import com.agentstore.external.config.ExternalApiConfiguration
import com.agentstore.external.config.ExternalApiProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class ExternalApiConfigurationTest {
    @Test
    fun `external API rejects a non-default HTTPS port`() {
        val configuration = ExternalApiConfiguration()
        val properties = ExternalApiProperties(
            publicBaseUrl = "https://api.example.com:8443",
            payTo = "0x0000000000000000000000000000000000000001",
            facilitatorUrl = "https://facilitator.example.com",
            facilitatorRequestTimeout = Duration.ofSeconds(5),
            authorizationTimeout = Duration.ofSeconds(60),
            feeBasisPoints = 250,
            intentTtl = Duration.ofMinutes(5),
            receiptTtl = Duration.ofMinutes(15),
            rateLimitPerMinute = 30,
        )

        assertThrows(IllegalArgumentException::class.java) {
            configuration.externalX402PaymentService(
                properties = properties,
                facilitatorClient = mock(FacilitatorIncomingPaymentClient::class.java),
                objectMapper = jacksonObjectMapper(),
            )
        }
    }
}
