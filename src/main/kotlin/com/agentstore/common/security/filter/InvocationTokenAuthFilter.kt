package com.agentstore.common.security.filter

import com.agentstore.common.security.exception.AgentStoreAuthenticationException
import com.agentstore.common.security.helper.InvocationTokenAuthHelper
import com.agentstore.common.security.handler.AgentStoreAuthenticationEntryPoint
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Suppress("NamedArguments")
class InvocationTokenAuthFilter(
    private val helper: InvocationTokenAuthHelper,
    private val authenticationEntryPoint: AgentStoreAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    companion object {
        private val CALLBACK_PATH = Regex("/api/runtime/executions/[^/]+/dependencies/invoke")
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return request.method != "POST" || !CALLBACK_PATH.matches(path(request))
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val principal = helper.authenticate(authorization = request.getHeader("Authorization"))
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

    private fun path(request: HttpServletRequest): String {
        return request.requestURI.removePrefix(request.contextPath)
    }
}
