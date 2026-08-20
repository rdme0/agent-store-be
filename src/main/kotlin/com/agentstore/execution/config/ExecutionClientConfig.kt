package com.agentstore.execution.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class ExecutionClientConfig {
    @Bean
    fun restClient(): RestClient {
        return RestClient.builder().build()
    }
}
