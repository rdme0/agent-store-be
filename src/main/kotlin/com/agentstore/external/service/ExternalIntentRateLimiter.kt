package com.agentstore.external.service

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.external.config.ExternalApiProperties
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

class ExternalIntentRateLimiter(
    private val properties: ExternalApiProperties,
    private val clock: Clock,
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun requireAllowed(remoteAddress: String) {
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MINUTES)
        val bucket = buckets.compute(remoteAddress) { _, current ->
            when (current?.window) {
                now -> current.copy(requests = current.requests + 1)
                else -> Bucket(window = now, requests = 1)
            }
        } ?: error("external_rate_limit_bucket_missing")
        if (bucket.requests > properties.rateLimitPerMinute) {
            throw DomainClientException(ErrorCode.EXTERNAL_RATE_LIMITED)
        }
    }

    private data class Bucket(val window: Instant, val requests: Int)
}
