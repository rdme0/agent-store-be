package com.agentstore.x402.client

import com.agentstore.agent.model.vo.ValidatedAgentEndpoint
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.payment.client.PinnedAgentRestClientFactory
import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.x402.dto.internal.X402AgentResponseDto
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

class X402AgentClient(
    private val endpointPolicy: AgentEndpointPolicy,
    private val pinnedClientFactory: PinnedAgentRestClientFactory,
) {
    companion object {
        private const val PAYMENT_SIGNATURE = "PAYMENT-SIGNATURE"
        const val MAX_BODY_BYTES = 1_048_576
    }

    fun prepare(endpoint: String): X402AgentConnection {
        return X402AgentConnection(endpoint = endpointPolicy.resolve(endpoint = endpoint))
    }

    fun post(
        connection: X402AgentConnection,
        request: PaymentInvocationRequestDto,
        body: ByteArray,
        paymentSignature: String?,
        deadline: Long,
    ): X402AgentResponseDto {
        val remaining = deadline - System.nanoTime()
        require(remaining > 0) { "agent_request_deadline_exceeded" }
        return pinnedClientFactory.withPinnedClient(
            endpoint = connection.endpoint,
            timeout = Duration.ofNanos(remaining),
        ) { restClient ->
            restClient.post()
                .uri(connection.endpoint.uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { headers ->
                    request.invocationToken.takeIf(String::isNotBlank)?.let { token ->
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    }
                }
                .header("Idempotency-Key", request.idempotencyKey)
                .headers { headers -> paymentSignature?.let { headers.set(PAYMENT_SIGNATURE, it) } }
                .body(body)
                .exchange { _, response ->
                    X402AgentResponseDto(
                        status = response.statusCode.value(),
                        headers = HttpHeaders().also { it.putAll(response.headers) },
                        body = readBounded(input = response.body, deadline = deadline),
                    )
                }
        }
    }

    private fun readBounded(input: InputStream, deadline: Long): ByteArray {
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                require(System.nanoTime() < deadline) { "agent_request_deadline_exceeded" }
                val read = stream.read(buffer)
                require(System.nanoTime() < deadline) { "agent_request_deadline_exceeded" }
                if (read < 0) {
                    return@use output.toByteArray()
                }

                total += read
                require(total <= MAX_BODY_BYTES) { "agent_response_too_large" }
                output.write(buffer, 0, read)
            }
            error("unreachable")
        }
    }

}

class X402AgentConnection internal constructor(
    internal val endpoint: ValidatedAgentEndpoint,
)
