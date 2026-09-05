package com.agentstore.common.security.helper

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.security.dto.InvocationPrincipal
import com.agentstore.common.security.exception.AgentStoreAuthenticationException
import com.agentstore.execution.token.InvocationTokenService
import java.time.Instant
import org.springframework.stereotype.Component

@Component
class InvocationTokenAuthHelper(
    private val tokenService: InvocationTokenService,
) {
    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }

    fun authenticate(authorization: String?): InvocationPrincipal {
        val token = authorization
            ?.trim()
            ?.takeIf { value -> value.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?: throw AgentStoreAuthenticationException(ErrorCode.INVALID_INVOCATION_TOKEN)

        return try {
            val claims = tokenService.verify(token)
            InvocationPrincipal(
                executionId = claims.executionId,
                stepId = claims.stepId,
                agentVersionId = claims.agentVersionId,
                callPath = claims.callPath,
                expiresAt = Instant.ofEpochSecond(claims.expiresAtEpochSeconds),
            )
        } catch (exception: DomainClientException) {
            throw AgentStoreAuthenticationException(
                errorCode = exception.errorCode,
                cause = exception,
            )
        }
    }
}
