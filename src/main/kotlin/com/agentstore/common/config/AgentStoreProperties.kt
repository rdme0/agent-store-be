package com.agentstore.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent-store")
data class AgentStoreProperties(
    val serviceName: String,
    val apiVersion: String,
    val runtimeCallbackBaseUrl: String,
    val corsOrigins: List<String>,
    val runtimeTokenSecret: String,
    val paymentMode: String,
)
