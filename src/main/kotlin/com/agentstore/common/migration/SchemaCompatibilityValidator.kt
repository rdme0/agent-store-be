package com.agentstore.common.migration

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

class SchemaCompatibilityException(message: String) : IllegalStateException(message)

@Component
class SchemaCompatibilityValidator(private val jdbcTemplate: JdbcTemplate) {
    private val requiredTables = setOf(
        "users", "developers", "agents", "agent_versions", "agent_dependencies", "execution_quotes",
        "executions", "execution_steps", "execution_events", "payment_attempts",
        "payment_settlement_journals", "revenue_entries"
    )

    fun assertExistingAgentStoreSchema() {
        val tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'",
            String::class.java
        ).toSet() - "flyway_schema_history" - "_prisma_migrations"
        if (tables != requiredTables) {
            throw SchemaCompatibilityException(
                "Existing AgentStore schema does not match the expected Prisma schema; missing=${requiredTables - tables}, unexpected=${tables - requiredTables}"
            )
        }
        val enumTypes = jdbcTemplate.queryForList(
            "select t.typname from pg_type t join pg_namespace n on n.oid = t.typnamespace where n.nspname = 'public' and t.typtype = 'e'",
            String::class.java
        ).toSet()
        val requiredEnums = setOf("AgentVersionStatus", "ExecutionStatus", "ExecutionStepStatus", "PaymentAttemptStatus", "PaymentMode", "RevenueType")
        if (!enumTypes.containsAll(requiredEnums)) {
            throw SchemaCompatibilityException("Existing AgentStore schema is missing enum types: ${requiredEnums - enumTypes}")
        }
    }
}
