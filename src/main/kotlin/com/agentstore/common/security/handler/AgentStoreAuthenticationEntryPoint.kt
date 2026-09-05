package com.agentstore.common.security.handler

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.security.exception.AgentStoreAuthenticationException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
class AgentStoreAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    companion object {
        private const val TRACE_HEADER = "X-Trace-Id"
        private val CALLBACK_PATH = Regex("/api/runtime/executions/[^/]+/dependencies/invoke")
        private val EXTERNAL_PATH = Regex("/v1/invocations/[^/]+(?:/events)?")
        private val log = LoggerFactory.getLogger(AgentStoreAuthenticationEntryPoint::class.java)
    }

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val errorCode = when (authException) {
            is AgentStoreAuthenticationException -> authException.errorCode
            else -> errorCode(path = path(request))
        }
        val traceId = MDC.get("traceId")?.takeIf(String::isNotBlank)
            ?: request.getHeader(TRACE_HEADER)?.trim()?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()

        response.status = errorCode.status.value()
        response.contentType = "application/json"
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(TRACE_HEADER, traceId)
        log.warn("Authentication failed: code={}, path={}", errorCode.code, path(request))
        objectMapper.writeValue(
            response.writer,
            CommonResponse.failure(errorCode = errorCode),
        )
    }

    private fun errorCode(path: String): ErrorCode {
        return when {
            CALLBACK_PATH.matches(path) -> ErrorCode.INVALID_INVOCATION_TOKEN
            EXTERNAL_PATH.matches(path) -> ErrorCode.EXTERNAL_INVOCATION_NOT_FOUND
            else -> ErrorCode.DEMO_AUTH_REQUIRED
        }
    }

    private fun path(request: HttpServletRequest): String {
        return request.requestURI.removePrefix(request.contextPath)
    }
}
