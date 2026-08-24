package com.agentstore.external.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent-store.external-api")
data class ExternalApiProperties(
    val publicBaseUrl: String,
    val payTo: String,
    val facilitatorUrl: String,
    val facilitatorRequestTimeout: Duration,
    val authorizationTimeout: Duration,
    val feeBasisPoints: Int,
    val intentTtl: Duration,
    val receiptTtl: Duration,
    val rateLimitPerMinute: Int,
)
