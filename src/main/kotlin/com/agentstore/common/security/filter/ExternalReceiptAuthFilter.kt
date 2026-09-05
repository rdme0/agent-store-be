package com.agentstore.common.security.filter

import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.security.constant.SecurityPath
import com.agentstore.common.security.exception.AgentStoreAuthenticationException
import com.agentstore.common.security.handler.AgentStoreAuthenticationEntryPoint
import com.agentstore.common.security.helper.ExternalReceiptAuthHelper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Suppress("NamedArguments")
class ExternalReceiptAuthFilter(
    private val helper: ExternalReceiptAuthHelper,
    private val authenticationEntryPoint: AgentStoreAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    companion object {
        private const val RECEIPT_HEADER = "X-AgentStore-Invocation-Receipt"
        private val UUID_PATTERN = Regex(SecurityPath.UUID_PATTERN)
        private val STATUS_PATH = Regex("/v1/invocations/([^/]+)")
        private val EVENTS_PATH = Regex("/v1/invocations/([^/]+)/events")
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val isReadRequest = request.method == "GET" || request.method == "HEAD"
        return !isReadRequest || !isInvocationPath(request)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val invocationId = invocationId(request)
        if (invocationId == null) {
            SecurityContextHolder.clearContext()
            authenticationEntryPoint.commence(
                request,
                response,
                AgentStoreAuthenticationException(ErrorCode.INVALID_INPUT_VALUE),
            )
            return
        }

        try {
            val principal = helper.authenticate(
                invocationId = invocationId,
                receiptToken = request.getHeader(RECEIPT_HEADER),
            )
            val authentication = UsernamePasswordAuthenticationToken(
                principal,
                null,
                emptyList(),
            )
            SecurityContextHolder.getContext().authentication = authentication
            filterChain.doFilter(request, response)
        } catch (exception: AgentStoreAuthenticationException) {
            SecurityContextHolder.clearContext()
            authenticationEntryPoint.commence(request, response, exception)
        }
    }

    private fun invocationId(request: HttpServletRequest): UUID? {
        val matcher = EVENTS_PATH.matchEntire(path(request)) ?: STATUS_PATH.matchEntire(path(request))
            ?: return null
        val rawInvocationId = matcher.groupValues[1]
        if (!rawInvocationId.matches(UUID_PATTERN)) {
            return null
        }

        return try {
            UUID.fromString(rawInvocationId)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isInvocationPath(request: HttpServletRequest): Boolean {
        val requestPath = path(request)
        return STATUS_PATH.matches(requestPath) || EVENTS_PATH.matches(requestPath)
    }

    private fun path(request: HttpServletRequest): String {
        return request.requestURI.removePrefix(request.contextPath)
    }
}
