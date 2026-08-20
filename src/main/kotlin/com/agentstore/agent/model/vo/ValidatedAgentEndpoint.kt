package com.agentstore.agent.model.vo

import java.net.InetAddress
import java.net.URI

data class ValidatedAgentEndpoint(
    val uri: URI,
    val addresses: List<InetAddress>,
)
