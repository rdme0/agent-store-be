package com.agentstore.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent-store")
data class AgentStoreProperties(
    val serviceName: String = "agent-store-api",
    val apiVersion: String = "0.1.0",
    val runtimeCallbackBaseUrl: String = "http://localhost:8080",
    val corsOrigins: List<String>,
    val runtimeTokenSecret: String,
    val databaseUrl: String,
    val paymentMode: String = "simulated",
    val x402BridgeUrl: String = "http://127.0.0.1:8091",
    val x402BridgeSecret: String = "",
)
