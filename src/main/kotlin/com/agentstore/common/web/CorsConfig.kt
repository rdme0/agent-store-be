package com.agentstore.common.web

import com.agentstore.common.config.AgentStoreProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CorsConfig(private val properties: AgentStoreProperties) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        val origins = properties.corsOrigin.split(',').map(String::trim).filter(String::isNotBlank)
        val mapping = registry.addMapping("/**")
            .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("X-Trace-Id", "Last-Event-ID")
            .allowCredentials(origins.none { it == "*" })
        if (origins.contains("*")) {
            mapping.allowedOriginPatterns("*")
        } else {
            mapping.allowedOrigins(*origins.toTypedArray())
        }
    }
}
