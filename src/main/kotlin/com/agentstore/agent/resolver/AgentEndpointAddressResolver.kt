package com.agentstore.agent.resolver

import java.net.InetAddress

fun interface AgentEndpointAddressResolver {
    fun resolve(host: String): List<InetAddress>
}
