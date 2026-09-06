package com.agentstore.execution.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.payment.client.BithumbKrwRateClient
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock

@Configuration
class ExecutionClientConfig {
    @Bean
    fun bithumbKrwRateClient(
        properties: AgentStoreProperties,
        objectMapper: ObjectMapper,
    ): BithumbKrwRateClient {
        return BithumbKrwRateClient.create(properties = properties, objectMapper = objectMapper)
    }

    @Bean
    fun clock(): Clock {
        return Clock.systemUTC()
    }
}
