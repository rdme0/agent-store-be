package com.agentstore.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {
    companion object {
        private const val DEMO_BEARER_SCHEME = "demoBearer"
    }

    @Bean
    fun publicOpenApi(properties: AgentStoreProperties): OpenAPI {
        return OpenAPI().servers(
            listOf(
                Server()
                    .url(properties.backendUrl)
                    .description("Configured public API URL"),
            ),
        )
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
        }
    }
}
