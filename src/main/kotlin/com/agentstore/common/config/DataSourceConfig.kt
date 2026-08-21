package com.agentstore.common.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(AgentStoreProperties::class)
class DataSourceConfig {
    @Bean
    fun dataSource(
        properties: AgentStoreProperties,
    ): HikariDataSource {
        val parsed = DatabaseUrlParser.parse(properties.databaseUrl)
        return HikariDataSource().apply {
            jdbcUrl = parsed.jdbcUrl
            username = parsed.username
            password = parsed.password
            maximumPoolSize = 10
            poolName = "agent-store-pool"
        }
    }
}
