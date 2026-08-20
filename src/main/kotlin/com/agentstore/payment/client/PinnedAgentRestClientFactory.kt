package com.agentstore.payment.client

import com.agentstore.agent.model.vo.ValidatedAgentEndpoint
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class PinnedAgentRestClientFactory {
    /**
     * The Apache route retains the validated URI hostname for HTTP Host and TLS SNI,
     * while [PinnedAgentDnsResolver] limits the socket connection to the validated IP set.
     */
    fun <T> withPinnedClient(endpoint: ValidatedAgentEndpoint, action: (RestClient) -> T): T {
        val manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(PinnedAgentDnsResolver(endpoint))
            .build()
        HttpClients.custom()
            .setConnectionManager(manager)
            .disableRedirectHandling()
            .build()
            .use { httpClient ->
                val client = RestClient.builder()
                    .requestFactory(HttpComponentsClientHttpRequestFactory(httpClient))
                    .build()
                return action(client)
            }
    }
}
