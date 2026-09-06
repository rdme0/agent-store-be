package com.agentstore.external.config

import java.math.BigInteger
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent-store.external-api")
data class ExternalInvocationProperties(
    val receiptTtl: Duration,
    val rateLimitPerMinute: Int,
    val maxCostAtomic: BigInteger,
) {
    init {
        require(!receiptTtl.isZero && !receiptTtl.isNegative) {
            "agent-store.external-api.receipt-ttl must be positive"
        }
        require(rateLimitPerMinute > 0) {
            "agent-store.external-api.rate-limit-per-minute must be positive"
        }
        require(maxCostAtomic > BigInteger.ZERO) {
            "agent-store.external-api.max-cost-atomic must be positive"
        }
    }
}
