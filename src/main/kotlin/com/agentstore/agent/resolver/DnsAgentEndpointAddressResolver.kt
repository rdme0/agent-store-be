package com.agentstore.agent.resolver

import org.springframework.stereotype.Component
import java.net.InetAddress

@Component
class DnsAgentEndpointAddressResolver : AgentEndpointAddressResolver {
    override fun resolve(host: String): List<InetAddress> {
        return InetAddress.getAllByName(host).toList()
    }
}
