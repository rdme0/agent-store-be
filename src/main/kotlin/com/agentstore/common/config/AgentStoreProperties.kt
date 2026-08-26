package com.agentstore.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "agent-store")
data class AgentStoreProperties(
    val serviceName: String,
    val apiVersion: String,
    val runtimeCallbackBaseUrl: String,
    val corsOrigins: List<String>,
    val runtimeTokenSecret: String,
    val bithumbApiUrl: String,
    val bithumbRequestTimeout: Duration,
    val bithumbCacheTtl: Duration,
    val bithumbStaleTtl: Duration,
)
