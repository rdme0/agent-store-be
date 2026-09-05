package com.agentstore.common.security.helper

import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.security.dto.ExternalReceiptPrincipal
import com.agentstore.common.security.exception.AgentStoreAuthenticationException
import com.agentstore.external.repository.ExternalInvocationIntentRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ExternalReceiptAuthHelper(
    private val intentRepository: ExternalInvocationIntentRepository,
    private val clock: Clock,
) {
    fun authenticate(invocationId: UUID, receiptToken: String?): ExternalReceiptPrincipal {
        val token = receiptToken
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw notFound()
        val intent = intentRepository.findById(invocationId).orElse(null) ?: throw notFound()

        if (!intent.receiptExpiresAt.isAfter(Instant.now(clock))) {
            throw notFound()
        }

        val suppliedTokenHash = hash(value = token.toByteArray(StandardCharsets.UTF_8))
        val tokenMatches = MessageDigest.isEqual(
            suppliedTokenHash.toByteArray(StandardCharsets.UTF_8),
            intent.receiptTokenHash.toByteArray(StandardCharsets.UTF_8),
        )
        if (!tokenMatches) {
            throw notFound()
        }

        return ExternalReceiptPrincipal(
            invocationId = intent.id,
            expiresAt = intent.receiptExpiresAt,
        )
    }

    private fun notFound(): AgentStoreAuthenticationException {
        return AgentStoreAuthenticationException(ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND)
    }

    private fun hash(value: ByteArray): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
    }
}
