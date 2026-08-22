package com.agentstore.payment.client

import com.agentstore.agent.model.vo.ValidatedAgentEndpoint
import java.net.InetAddress
import java.net.UnknownHostException
import org.apache.hc.client5.http.DnsResolver

class PinnedAgentDnsResolver(private val endpoint: ValidatedAgentEndpoint) : DnsResolver {
    override fun resolve(host: String): Array<InetAddress> {
        val endpointHost = endpoint.uri.host.trim('[', ']')
        if (!host.equals(other = endpointHost, ignoreCase = true)) {
            throw UnknownHostException(host)
        }
        return endpoint.addresses.toTypedArray()
    }

    override fun resolveCanonicalHostname(host: String): String {
        return host
    }
}
