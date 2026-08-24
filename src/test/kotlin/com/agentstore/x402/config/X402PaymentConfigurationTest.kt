package com.agentstore.x402.config

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.config.PaymentClientConfiguration
import com.agentstore.x402.registry.X402PaymentCorrelationRegistry
import com.agentstore.x402.service.X402PaymentService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class X402PaymentConfigurationTest {
    private val configuration = PaymentClientConfiguration()
    private val properties = AgentStoreProperties(
        serviceName = "agent-store-api",
        apiVersion = "0.1.0",
        runtimeCallbackBaseUrl = "http://127.0.0.1:8080",
        corsOrigins = listOf("http://localhost:*"),
        runtimeTokenSecret = "test-runtime-secret",
        paymentMode = "x402",
        bithumbApiUrl = "https://api.bithumb.com",
        bithumbRequestTimeout = Duration.ofSeconds(2),
        bithumbCacheTtl = Duration.ofSeconds(60),
        bithumbStaleTtl = Duration.ofMinutes(15),
    )
    private val endpointPolicy =
        AgentEndpointPolicy(MockEnvironment().apply { setActiveProfiles("test") }) {
            error("loopback endpoints do not resolve DNS")
        }

    @Test
    fun `x402 mode requires a valid private key before serving requests`() {
        assertThatThrownBy {
            configuration.paymentClient(
                properties,
                endpointPolicy,
                PinnedAgentRestClientFactory(),
                jacksonObjectMapper(),
                MockEnvironment(),
                X402PaymentCorrelationRegistry(),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("X402_PRIVATE_KEY")
    }

    @Test
    fun `x402 mode creates one native client for payment and reconciliation`() {
        val environment = MockEnvironment().withProperty(
            "X402_PRIVATE_KEY",
            "0x1111111111111111111111111111111111111111111111111111111111111111",
        )
        val client = configuration.paymentClient(
            properties,
            endpointPolicy,
            PinnedAgentRestClientFactory(),
            jacksonObjectMapper(),
            environment,
            X402PaymentCorrelationRegistry(),
        )

        assertThat(client).isInstanceOf(X402PaymentService::class.java)
        assertThat(configuration.paymentReconciliationClient(properties, client)).isSameAs(client)
    }
}
