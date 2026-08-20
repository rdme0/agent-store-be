package com.agentstore.payment.client

import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult
import com.agentstore.payment.model.vo.PaymentMode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType

class SimulatedPaymentClient(
    private val endpointPolicy: AgentEndpointPolicy,
    private val pinnedClientFactory: PinnedAgentRestClientFactory,
    private val objectMapper: ObjectMapper,
) : PaymentClient {
    override val mode = PaymentMode.SIMULATED

    override fun invoke(request: PaymentInvocationRequest): PaymentInvocationResult {
        val endpoint = endpointPolicy.resolve(request.endpoint)
        val output = pinnedClientFactory.withPinnedClient(endpoint) { restClient ->
            restClient.post()
                .uri(endpoint.uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-AgentStore-Invocation-Token", request.invocationToken)
                .header("Authorization", "Bearer ${request.invocationToken}")
                .header("Idempotency-Key", request.idempotencyKey)
                .body(objectMapper.writeValueAsString(request.body ?: emptyMap<String, Any>()))
                .retrieve()
                .onStatus({ status -> status.is3xxRedirection }) { _, _ -> throw IllegalStateException("Agent endpoint redirects are not allowed") }
                .body(String::class.java)
                ?.takeIf { it.isNotBlank() }
                ?.let(objectMapper::readTree)
                ?: error("Agent returned an empty response")
        }
        return PaymentInvocationResult(output)
    }
}
