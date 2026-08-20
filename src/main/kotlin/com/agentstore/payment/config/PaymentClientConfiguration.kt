package com.agentstore.payment.config

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.payment.client.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PaymentClientConfiguration {
    @Bean
    fun paymentClient(
        properties: AgentStoreProperties,
        endpointPolicy: AgentEndpointPolicy,
        pinnedClientFactory: PinnedAgentRestClientFactory,
        objectMapper: ObjectMapper
    ): PaymentClient {
        return when (properties.paymentMode) {
            "simulated" -> SimulatedPaymentClient(endpointPolicy, pinnedClientFactory, objectMapper)
            "x402" -> X402BridgePaymentClient(properties.x402BridgeUrl, properties.x402BridgeSecret, objectMapper)
            else -> error("PAYMENT_MODE must be simulated or x402")
        }
    }

    @Bean
    fun paymentReconciliationClient(
        properties: AgentStoreProperties,
        objectMapper: ObjectMapper
    ): PaymentReconciliationClient {
        return when (properties.paymentMode) {
            "simulated" -> NoopPaymentReconciliationClient()
            "x402" -> X402BridgeReconciliationClient(
                X402BridgePaymentClient.validatedBridgeUri(properties.x402BridgeUrl),
                properties.x402BridgeSecret,
                objectMapper,
            )

            else -> error("PAYMENT_MODE must be simulated or x402")
        }
    }
}
