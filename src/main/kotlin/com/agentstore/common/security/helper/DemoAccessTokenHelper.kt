package com.agentstore.common.security.helper

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.security.dto.DemoDeveloperPrincipal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

@Component
class DemoAccessTokenHelper(
    private val properties: AgentStoreProperties,
    private val clock: Clock,
) {
    companion object {
        val ACCESS_TTL: Duration = Duration.ofDays(365)
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val TOKEN_PURPOSE = "demo-access"
    }

    fun issue(developerId: UUID): IssuedDemoAccessToken {
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plus(ACCESS_TTL)
        val payload = "$TOKEN_PURPOSE.$developerId.${issuedAt.epochSecond}.${expiresAt.epochSecond}"
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encodedPayload))
        return IssuedDemoAccessToken(accessToken = "$encodedPayload.$signature", expiresAt = expiresAt)
    }

    fun authenticate(authorization: String?): DemoDeveloperPrincipal? {
        val token = authorization?.trim()?.takeIf { value -> value.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")?.takeIf(String::isNotBlank) ?: return null
        val parts = token.split('.')
        if (parts.size != 2) {
            return null
        }
        val expected = sign(parts[0])
        val actual = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return null
        }
        val values = try {
            Base64.getUrlDecoder().decode(parts[0]).toString(StandardCharsets.UTF_8).split('.')
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (values.size != 4 || values[0] != TOKEN_PURPOSE) {
            return null
        }
        val developerId = try {
            UUID.fromString(values[1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        val issuedAt = values[2].toLongOrNull()?.let(::safeInstant) ?: return null
        val expiresAt = values[3].toLongOrNull()?.let(::safeInstant) ?: return null
        val now = clock.instant()
        if (issuedAt.isAfter(now) || !expiresAt.isAfter(now)) {
            return null
        }
        return DemoDeveloperPrincipal(developerId = developerId)
    }

    private fun safeInstant(epochSecond: Long): Instant? {
        return try {
            Instant.ofEpochSecond(epochSecond)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun sign(value: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec("$TOKEN_PURPOSE:${properties.runtimeTokenSecret}".toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }
}

data class IssuedDemoAccessToken(
    val accessToken: String,
    val expiresAt: Instant,
)
