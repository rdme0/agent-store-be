package com.agentstore.x402.config

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.payment.client.PaymentReconciliationClient
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.config.PaymentClientConfiguration
import com.agentstore.payment.config.X402ClientProperties
import com.agentstore.x402.registry.X402PaymentCorrelationRegistry
import com.agentstore.x402.service.X402PaymentService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Clock
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
                properties = x402ClientProperties(),
                clock = Clock.systemUTC(),
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
            properties = x402ClientProperties(),
            clock = Clock.systemUTC(),
        )

        assertThat(client).isInstanceOf(X402PaymentService::class.java)
        assertThat(client).isInstanceOf(PaymentReconciliationClient::class.java)
    }

    @Test
    fun `native x402 payment rejects a non-positive aggregate invocation timeout`() {
        assertThatThrownBy {
            X402ClientProperties(perDepthInvocationTimeout = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("agent-store.x402-client.per-depth-invocation-timeout is invalid")
    }

    @Test
    fun `native x402 aggregate deadline follows the maximum execution graph depth`() {
        val properties = x402ClientProperties()

        assertThat(properties.aggregateInvocationTimeout()).isEqualTo(Duration.ofSeconds(150))
        assertThat(properties.invocationTimeout(callPathSize = 1)).isEqualTo(Duration.ofSeconds(150))
        assertThat(properties.invocationTimeout(callPathSize = 2)).isEqualTo(Duration.ofSeconds(120))
        assertThat(properties.invocationTimeout(callPathSize = 5)).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `native x402 deadline rejects a call path beyond the execution graph limit`() {
        assertThatThrownBy {
            x402ClientProperties().invocationTimeout(callPathSize = 6)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("execution call path depth is invalid")
    }

    private fun x402ClientProperties(): X402ClientProperties {
        return X402ClientProperties(perDepthInvocationTimeout = Duration.ofSeconds(30))
    }
}
