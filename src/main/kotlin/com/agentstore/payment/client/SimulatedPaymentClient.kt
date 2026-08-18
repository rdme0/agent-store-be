package com.agentstore.payment.client

import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class SimulatedPaymentClient(private val restClient: RestClient) : PaymentClient {
    override fun invoke(request: PaymentInvocationRequest): PaymentInvocationResult {
        val output = restClient.post()
            .uri(request.endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-AgentStore-Invocation-Token", request.invocationToken)
            .header("Idempotency-Key", request.idempotencyKey)
            .body(request.body ?: emptyMap<String, Any>())
            .retrieve()
            .body(JsonNode::class.java)
            ?: error("Agent returned an empty response")
        return PaymentInvocationResult(output)
    }
}
