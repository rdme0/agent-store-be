package com.agentstore.agent.config

import java.math.BigInteger
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent-store.provider-readiness")
data class ProviderReadinessProperties(
    val preflightInterval: Duration,
    val maxCertificationPriceAtomic: BigInteger,
)
