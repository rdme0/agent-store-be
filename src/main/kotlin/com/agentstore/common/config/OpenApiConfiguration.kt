package com.agentstore.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod

@Configuration
class OpenApiConfiguration {
    companion object {
        private const val DEMO_BEARER_SCHEME = "demoBearer"
    }

    @Bean
    fun demoBearerOpenApiCustomizer(): OpenApiCustomizer {
        return OpenApiCustomizer { openApi ->
            val components = openApi.components ?: Components().also { openApi.components = it }
            components.addSecuritySchemes(
                DEMO_BEARER_SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("Demo access token"),
            )
            openApi.paths?.forEach { (path, item) ->
                item.readOperationsMap().forEach { (method, operation) ->
                    if (requiresDemoBearer(path = path, method = method)) {
                        operation.addSecurityItem(SecurityRequirement().addList(DEMO_BEARER_SCHEME))
                    }
                }
            }
        }
    }

    private fun requiresDemoBearer(path: String, method: PathItem.HttpMethod): Boolean {
        val httpMethod = HttpMethod.valueOf(method.name)
        if (path.startsWith("/api/developer/")) {
            return true
        }
        if (path.matches(Regex("/api/developers/[^/]+/revenue"))) {
            return httpMethod == HttpMethod.GET
        }
        if (path == "/api/agents") {
            return httpMethod == HttpMethod.POST
        }
        if (path.matches(Regex("/api/agents/[^/]+"))) {
            return httpMethod == HttpMethod.PATCH || httpMethod == HttpMethod.DELETE
        }
        if (path.matches(Regex("/api/agents/[^/]+/versions"))) {
            return httpMethod == HttpMethod.POST
        }
        if (path == "/api/function-contracts" || path == "/api/agent-manifests") {
            return httpMethod == HttpMethod.POST
        }
        if (path.matches(Regex("/api/agent-versions/[^/]+/manifest"))) {
            return httpMethod == HttpMethod.GET || httpMethod == HttpMethod.PUT
        }
        if (path.matches(Regex("/api/agent-manifests/agent-versions/[^/]+"))) {
            return httpMethod == HttpMethod.GET
        }
        if (path.matches(Regex("/api/agent-versions/[^/]+/(readiness|dependencies)"))) {
            return httpMethod == HttpMethod.GET || httpMethod == HttpMethod.POST
        }
        if (path.matches(Regex("/api/agent-versions/[^/]+/(publish|verify|disable|verification-input/backfill)"))) {
            return httpMethod == HttpMethod.POST
        }
        if (path.matches(Regex("/api/agent-versions/[^/]+/dependencies(?:/[^/]+)?"))) {
            return httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PATCH || httpMethod == HttpMethod.DELETE
        }
        return false
    }
}
