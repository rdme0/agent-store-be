package com.agentstore.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent-store")
data class AgentStoreProperties(
    val corsOrigin: String,
    val runtimeTokenSecret: String,
    val databaseUrl: String,
    val testDatabaseUrl: String,
)
