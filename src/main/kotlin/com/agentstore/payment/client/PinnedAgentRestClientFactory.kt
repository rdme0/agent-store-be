package com.agentstore.payment.client

import com.agentstore.agent.model.vo.ValidatedAgentEndpoint
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.io.CloseMode
import org.apache.hc.core5.util.Timeout
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class PinnedAgentRestClientFactory {
    /**
     * The Apache route retains the validated URI hostname for HTTP Host and TLS SNI,
     * while [PinnedAgentDnsResolver] limits the socket connection to the validated IP set.
     */

    private companion object {
        val DEADLINE_EXECUTOR: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "agent-http-hard-deadline").apply { isDaemon = true }
            }
    }

    fun <T> withPinnedClient(
        endpoint: ValidatedAgentEndpoint,
        timeout: Duration,
        action: (RestClient) -> T,
    ): T {
        require(!timeout.isZero && !timeout.isNegative) { "agent_request_deadline_exceeded" }
        val connectTimeout = minOf(a = timeout, b = Duration.ofSeconds(5))
        val manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(PinnedAgentDnsResolver(endpoint))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(
                        Timeout.ofMilliseconds(
                            connectTimeout.toMillis().coerceAtLeast(1)
                        )
                    )
                    .setSocketTimeout(Timeout.ofMilliseconds(timeout.toMillis().coerceAtLeast(1)))
                    .build()
            )
            .build()
        val httpClient = HttpClients.custom()
            .setConnectionManager(manager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(
                        Timeout.ofMilliseconds(
                            connectTimeout.toMillis().coerceAtLeast(1)
                        )
                    )
                    .setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis().coerceAtLeast(1)))
                    .build()
            )
            // A paid x402 POST must never be replayed by the transport after a
            // non-success response; the caller owns reconciliation explicitly.
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build()
        val deadlineTask = DEADLINE_EXECUTOR.schedule(
            { runCatching { httpClient.close(CloseMode.IMMEDIATE) } },
            timeout.toNanos(),
            TimeUnit.NANOSECONDS,
        )
        return try {
            httpClient.use {
                val requestFactory = HttpComponentsClientHttpRequestFactory(httpClient).apply {
                    setConnectionRequestTimeout(connectTimeout)
                    setReadTimeout(timeout)
                }
                val client = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build()
                action(client)
            }
        } finally {
            deadlineTask.cancel(false)
        }
    }

}
