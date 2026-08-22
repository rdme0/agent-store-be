package com.agentstore.agent.resolver

import java.net.InetAddress
import org.springframework.stereotype.Component

@Component
class DnsAgentEndpointAddressResolver : AgentEndpointAddressResolver {
    override fun resolve(host: String): List<InetAddress> {
        return InetAddress.getAllByName(host).toList()
    }
}
