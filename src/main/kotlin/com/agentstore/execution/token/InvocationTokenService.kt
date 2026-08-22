package com.agentstore.execution.token

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

@Component
class InvocationTokenService(
    private val properties: AgentStoreProperties,
    private val objectMapper: ObjectMapper,
) {
    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val TTL_SECONDS = 300L
    }

    fun issue(
        executionId: UUID,
        stepId: UUID,
        agentVersionId: UUID,
        callPath: List<String>
    ): String {
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
        if (parts.size != 2) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        val expected = sign(parts[0].toByteArray(StandardCharsets.UTF_8))
        val actual = runCatching {
            Base64.getUrlDecoder().decode(parts[1])
        }.getOrElse {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        val payload = runCatching {
            objectMapper.readTree(
                Base64.getUrlDecoder().decode(parts[0])
            )
        }.getOrElse {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INVOCATION_TOKEN)
        }
        val expiresAt = payload.path("expiresAt").asLong(0)
        if (expiresAt <= Instant.now().epochSecond) {
            throw DomainClientException(ErrorCode.INVOCATION_TOKEN_EXPIRED)
        }
        return try {
            InvocationTokenClaims(
                executionId = UUID.fromString(payload.path("executionId").asText()),
                stepId = UUID.fromString(payload.path("stepId").asText()),
                agentVersionId = UUID.fromString(payload.path("agentVersionId").asText()),
                callPath = payload.path("callPath").map { node -> node.asText() },
                expiresAtEpochSeconds = expiresAt,
            )
        } catch (exception: IllegalArgumentException) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INVOCATION_TOKEN)
        }
    }

    private fun sign(value: ByteArray): ByteArray {
        return Mac.getInstance(ALGORITHM).run {
            init(
                SecretKeySpec(
                    properties.runtimeTokenSecret.toByteArray(StandardCharsets.UTF_8),
                    ALGORITHM
                )
            )
            doFinal(value)
        }
    }

    private fun encode(value: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

}
