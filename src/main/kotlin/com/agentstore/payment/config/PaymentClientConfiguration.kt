package com.agentstore.payment.config

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.payment.client.PinnedAgentRestClientFactory
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
    fun x402PaymentService(
        endpointPolicy: AgentEndpointPolicy,
        pinnedClientFactory: PinnedAgentRestClientFactory,
        objectMapper: ObjectMapper,
        environment: Environment,
        correlations: X402PaymentCorrelationRegistry,
    ): X402PaymentService {
        return X402PaymentService(
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
    }
}
