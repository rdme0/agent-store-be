package com.agentstore.common.security.config

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.security.constant.SecurityPath
import com.agentstore.common.security.filter.ExternalReceiptAuthFilter
import com.agentstore.common.security.filter.InvocationTokenAuthFilter
import com.agentstore.common.security.filter.DemoDeveloperBearerAuthFilter
import com.agentstore.common.security.handler.AgentStoreAuthenticationEntryPoint
import com.agentstore.common.web.TraceIdFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.RegexRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val properties: AgentStoreProperties,
    private val invocationTokenAuthFilter: InvocationTokenAuthFilter,
    private val externalReceiptAuthFilter: ExternalReceiptAuthFilter,
    private val demoDeveloperBearerAuthFilter: DemoDeveloperBearerAuthFilter,
    private val authenticationEntryPoint: AgentStoreAuthenticationEntryPoint,
) {
    companion object {
        private const val CORS_MAX_AGE = 3600L
        private const val INVOCATION_PATH = "[^/]+"
        private val EXTERNAL_GET_MATCHERS = arrayOf(
            RegexRequestMatcher("^/v1/invocations/$INVOCATION_PATH$", HttpMethod.GET.name()),
            RegexRequestMatcher("^/v1/invocations/$INVOCATION_PATH/events$", HttpMethod.GET.name()),
        )
        private val EXTERNAL_HEAD_MATCHERS = arrayOf(
            RegexRequestMatcher("^/v1/invocations/$INVOCATION_PATH$", HttpMethod.HEAD.name()),
            RegexRequestMatcher("^/v1/invocations/$INVOCATION_PATH/events$", HttpMethod.HEAD.name()),
        )
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.setAllowedOriginPatterns(properties.corsOrigins)
        configuration.setAllowedMethods(listOf("GET", "POST", "PATCH", "DELETE", "OPTIONS"))
        configuration.setAllowedHeaders(
            listOf(
                "Accept",
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
                "Last-Event-ID",
                "PAYMENT-SIGNATURE",
                "PAYMENT-RESPONSE",
                "X-AgentStore-Invocation-Receipt",
            ),
        )
        configuration.setExposedHeaders(
            listOf(
                "X-Trace-Id",
                "Last-Event-ID",
                "PAYMENT-REQUIRED",
                "PAYMENT-RESPONSE",
                "X-AgentStore-Invocation-Receipt",
            ),
        )
        configuration.allowCredentials = false
        configuration.maxAge = CORS_MAX_AGE

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .cors { cors -> cors.configurationSource(corsConfigurationSource()) }
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.POST, SecurityPath.RUNTIME_CALLBACK)
                    .authenticated()
                    .requestMatchers(*EXTERNAL_GET_MATCHERS)
                    .authenticated()
                    .requestMatchers(*EXTERNAL_HEAD_MATCHERS)
                    .authenticated()
                    .requestMatchers("/api/developer/**", "/api/developers/*/revenue")
                        .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/agent-versions/*/readiness",
                        "/api/agent-versions/*/dependencies",
                        "/api/agent-versions/*/manifest",
                        "/api/agent-manifests/agent-versions/*",
                    )
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/agents", "/api/agents/*/versions")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/agents/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/agents/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/agent-versions/*/publish",
                        "/api/agent-versions/*/verify",
                        "/api/agent-versions/*/disable",
                        "/api/agent-versions/*/verification-input/backfill",
                        "/api/agent-versions/*/dependencies/**",
                        "/api/function-contracts",
                        "/api/agent-manifests",
                    )
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/agent-versions/*/dependencies/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/agent-versions/*/dependencies/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/agent-versions/*/manifest")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
            }
            .exceptionHandling { exception ->
                exception.authenticationEntryPoint(authenticationEntryPoint)
            }
            .addFilterBefore(
                invocationTokenAuthFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )
            .addFilterBefore(
                TraceIdFilter(),
                InvocationTokenAuthFilter::class.java,
            )
            .addFilterBefore(demoDeveloperBearerAuthFilter, InvocationTokenAuthFilter::class.java)
            .addFilterBefore(
                externalReceiptAuthFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )
            .httpBasic { basic -> basic.disable() }
            .formLogin { login -> login.disable() }
            .build()
    }
}
