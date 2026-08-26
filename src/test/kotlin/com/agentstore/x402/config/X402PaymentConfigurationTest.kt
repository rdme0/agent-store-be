package com.agentstore.x402.config

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.payment.client.PaymentReconciliationClient
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.config.PaymentClientConfiguration
import com.agentstore.x402.registry.X402PaymentCorrelationRegistry
import com.agentstore.x402.service.X402PaymentService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class X402PaymentConfigurationTest {
    private val configuration = PaymentClientConfiguration()
    private val endpointPolicy =
        AgentEndpointPolicy(MockEnvironment().apply { setActiveProfiles("test") }) {
            error("loopback endpoints do not resolve DNS")
        }

    @Test
    fun `native x402 payment requires a valid private key before serving requests`() {
        assertThatThrownBy {
            configuration.x402PaymentService(
                endpointPolicy = endpointPolicy,
                pinnedClientFactory = PinnedAgentRestClientFactory(),
                objectMapper = jacksonObjectMapper(),
                environment = MockEnvironment(),
                correlations = X402PaymentCorrelationRegistry(),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("X402_PRIVATE_KEY")
    }

    @Test
    fun `native x402 payment implements payment and reconciliation clients`() {
        val environment = MockEnvironment().withProperty(
            "X402_PRIVATE_KEY",
            "0x1111111111111111111111111111111111111111111111111111111111111111",
        )
        val client = configuration.x402PaymentService(
            endpointPolicy = endpointPolicy,
            pinnedClientFactory = PinnedAgentRestClientFactory(),
            objectMapper = jacksonObjectMapper(),
            environment = environment,
            correlations = X402PaymentCorrelationRegistry(),
        )

        assertThat(client).isInstanceOf(X402PaymentService::class.java)
        assertThat(client).isInstanceOf(PaymentReconciliationClient::class.java)
    }
}
