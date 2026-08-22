package com.agentstore.common.config

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper {
        return JsonMapper.builder().findAndAddModules().build()
    }
}
