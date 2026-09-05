package com.agentstore.common.security.filter

import com.agentstore.common.security.constant.DemoSecurityPath
import com.agentstore.common.security.exception.AgentStoreAuthenticationException
import com.agentstore.common.security.helper.DemoAccessTokenHelper
import com.agentstore.common.security.handler.AgentStoreAuthenticationEntryPoint
import com.agentstore.common.exception.constants.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class DemoDeveloperBearerAuthFilter(
    private val helper: DemoAccessTokenHelper,
    private val authenticationEntryPoint: AgentStoreAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return !DemoSecurityPath.requiresDemoAccess(request)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = helper.authenticate(request.getHeader("Authorization"))
        if (principal == null) {
            SecurityContextHolder.clearContext()
            authenticationEntryPoint.commence(
                request = request,
                response = response,
                authException = AgentStoreAuthenticationException(errorCode = ErrorCode.DEMO_AUTH_REQUIRED),
            )
            return
        }
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            emptyList(),
        )
        filterChain.doFilter(request, response)
    }
}
