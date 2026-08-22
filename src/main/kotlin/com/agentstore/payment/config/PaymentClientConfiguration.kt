package com.agentstore.payment.config

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.payment.client.NoopPaymentReconciliationClient
import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.client.PaymentReconciliationClient
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.client.SimulatedPaymentClient
import com.agentstore.x402.client.X402AgentClient
import com.agentstore.x402.registry.X402PaymentCorrelationRegistry
import com.agentstore.x402.service.X402PaymentService
import com.agentstore.x402.signer.X402Eip3009Signer
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class PaymentClientConfiguration {
    companion object {
        private val X402_INVOCATION_DEADLINE = Duration.ofSeconds(30)
    }

    @Bean
    fun x402PaymentCorrelationRegistry(): X402PaymentCorrelationRegistry {
        return X402PaymentCorrelationRegistry()
    }

    @Bean
    fun paymentClient(
        properties: AgentStoreProperties,
        endpointPolicy: AgentEndpointPolicy,
        pinnedClientFactory: PinnedAgentRestClientFactory,
        objectMapper: ObjectMapper,
        environment: Environment,
        correlations: X402PaymentCorrelationRegistry,
    ): PaymentClient {
        return when (properties.paymentMode) {
            "simulated" -> SimulatedPaymentClient(
                endpointPolicy = endpointPolicy,
                pinnedClientFactory = pinnedClientFactory,
                objectMapper = objectMapper,
                invocationDeadline = X402_INVOCATION_DEADLINE,
            )
            "x402" -> X402PaymentService(
                agentClient = X402AgentClient(
                    endpointPolicy = endpointPolicy,
                    pinnedClientFactory = pinnedClientFactory,
                ),
                objectMapper = objectMapper,
                signer = X402Eip3009Signer(
                    privateKey = environment.getRequiredProperty("X402_PRIVATE_KEY"),
                    objectMapper = objectMapper,
                    clock = Clock.systemUTC(),
                    secureRandom = SecureRandom(),
                ),
                correlations = correlations,
                invocationDeadline = X402_INVOCATION_DEADLINE,
            )

            else -> error("PAYMENT_MODE must be simulated or x402")
        }
    }

    @Bean
    fun paymentReconciliationClient(
        properties: AgentStoreProperties,
        paymentClient: PaymentClient,
    ): PaymentReconciliationClient {
        return when (properties.paymentMode) {
            "simulated" -> NoopPaymentReconciliationClient()
            "x402" -> paymentClient as? X402PaymentService
                ?: error("native x402 payment client is not configured")

            else -> error("PAYMENT_MODE must be simulated or x402")
        }
    }
}
