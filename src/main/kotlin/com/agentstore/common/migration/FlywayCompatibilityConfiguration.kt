package com.agentstore.common.migration

import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "true")
class FlywayCompatibilityConfiguration {
    @Bean
    fun flywayMigrationStrategy(
        dataSource: DataSource,
        validator: SchemaCompatibilityValidator,
    ): FlywayMigrationStrategy = flywayMigrationStrategy(JdbcTemplate(dataSource), validator)

    internal fun flywayMigrationStrategy(
        jdbcTemplate: JdbcTemplate,
        validator: SchemaCompatibilityValidator,
    ): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway: Flyway ->
        val hasHistory = jdbcTemplate.queryForObject(
            "select to_regclass('public.flyway_schema_history') is not null",
            Boolean::class.java
        ) ?: false
        val hasAgentStoreTables = jdbcTemplate.queryForObject(
            "select exists (select 1 from information_schema.tables where table_schema = 'public' and table_name = 'users')",
            Boolean::class.java
        ) ?: false
        if (!hasHistory && hasAgentStoreTables) {
            validator.assertExistingAgentStoreSchema()
            flyway.baseline()
        }
        flyway.migrate()
    }
}
