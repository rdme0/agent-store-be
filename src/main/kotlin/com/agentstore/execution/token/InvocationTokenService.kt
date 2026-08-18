package com.agentstore.execution.token

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.web.ApiException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class InvocationTokenService(
    private val properties: AgentStoreProperties,
    private val objectMapper: ObjectMapper,
) {
    fun issue(executionId: UUID, stepId: UUID, agentVersionId: UUID, callPath: List<String>): String {
        val claims = mapOf(
            "executionId" to executionId,
            "stepId" to stepId,
            "agentVersionId" to agentVersionId,
            "callPath" to callPath,
            "expiresAt" to Instant.now().plusSeconds(TTL_SECONDS).epochSecond,
        )
        val payload = encode(objectMapper.writeValueAsBytes(claims))
        return "$payload.${encode(sign(payload.toByteArray(StandardCharsets.UTF_8)))}"
    }

    fun verify(token: String): InvocationTokenClaims {
        val parts = token.split('.')
        if (parts.size != 2) throw invalidToken()
        val expected = sign(parts[0].toByteArray(StandardCharsets.UTF_8))
        val actual = runCatching { Base64.getUrlDecoder().decode(parts[1]) }.getOrElse { throw invalidToken() }
        if (!MessageDigest.isEqual(expected, actual)) throw invalidToken()
        val payload = runCatching { objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0])) }.getOrElse { throw invalidToken() }
        val expiresAt = payload.path("expiresAt").asLong(0)
        if (expiresAt <= Instant.now().epochSecond) throw ApiException("INVOCATION_TOKEN_EXPIRED", "Invocation token has expired", 401)
        return try {
            InvocationTokenClaims(
                UUID.fromString(payload.path("executionId").asText()),
                UUID.fromString(payload.path("stepId").asText()),
                UUID.fromString(payload.path("agentVersionId").asText()),
                payload.path("callPath").map { it.asText() },
                expiresAt,
            )
        } catch (exception: IllegalArgumentException) {
            throw invalidToken()
        }
    }

    private fun sign(value: ByteArray): ByteArray = Mac.getInstance(ALGORITHM).run {
        init(SecretKeySpec(properties.runtimeTokenSecret.toByteArray(StandardCharsets.UTF_8), ALGORITHM))
        doFinal(value)
    }

    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun invalidToken() = ApiException("INVALID_INVOCATION_TOKEN", "Invocation token is invalid", 401)

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val TTL_SECONDS = 300L
    }
}
