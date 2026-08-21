package com.agentstore.common.migration

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import javax.sql.DataSource

/** Fail-closed Prisma V8 physical-schema fingerprint before Flyway baselines it. */
@Component
class SchemaCompatibilityValidator(dataSource: DataSource) {
    private val jdbcTemplate = JdbcTemplate(dataSource)
    fun assertExistingAgentStoreSchema() {
        assertSet(
            "tables",
            querySet("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'") - setOf(
                "flyway_schema_history",
                "_prisma_migrations"
            ),
            tables
        )
        assertSet(
            "enum types",
            querySet("select t.typname from pg_type t join pg_namespace n on n.oid=t.typnamespace where n.nspname='public' and t.typtype='e'"),
            enums
        )
        assertColumns()
        assertSet("primary keys", constraintNames("p"), tables.map { "${it}_pkey" }.toSet())
        assertSet("foreign keys", constraintNames("f"), foreignKeys)
        assertSet("check constraints", constraintNames("c"), checks)
        assertSet(
            "indexes",
            querySet("select indexname from pg_indexes where schemaname='public' and tablename not in ('_prisma_migrations', 'flyway_schema_history')") - tables.map { "${it}_pkey" }
                .toSet(),
            indexes
        )
    }

    private fun assertColumns() {
        val actual =
            jdbcTemplate.queryForList("select table_name,column_name,udt_name,is_nullable,coalesce(column_default,'') d from information_schema.columns where table_schema='public'")
                .filter { it["table_name"] in tables }
                .associate {
                    "${it["table_name"]}.${it["column_name"]}" to "${it["udt_name"]}|${it["is_nullable"]}|${
                        normalize(
                            it["d"].toString()
                        )
                    }"
                }
        if (actual != columns) {
            fail("columns", columns, actual)
        }
    }

    private fun querySet(sql: String): Set<String> {
        return jdbcTemplate.queryForList(sql, String::class.java).mapNotNull { it }.toSet()
    }

    private fun constraintNames(type: String): Set<String> {
        return jdbcTemplate.queryForList(
            "select c.conname from pg_constraint c join pg_namespace n on n.oid=c.connamespace join pg_class r on r.oid=c.conrelid where n.nspname='public' and r.relname not in ('_prisma_migrations', 'flyway_schema_history') and c.contype=?",
            String::class.java,
            type
        ).mapNotNull { it }.toSet()
    }

    private fun assertSet(label: String, actual: Set<String>, expected: Set<String>) {
        if (actual != expected) {
            fail(label, expected, actual)
        }
    }

    private fun fail(label: String, expected: Any, actual: Any): Nothing {
        throw SchemaCompatibilityException("Existing AgentStore Prisma V8 $label mismatch; expected=$expected actual=$actual")
    }

    private fun normalize(value: String): String {
        return value.lowercase().replace(Regex("\\s+"), " ").replace("'draft'::\"agentversionstatus\"", "draft")
            .replace("'pending'::\"executionstatus\"", "pending")
            .replace("'created'::\"executionstepstatus\"", "created")
            .replace("'required'::\"paymentattemptstatus\"", "required")
            .replace("'simulated'::\"paymentmode\"", "simulated")
            .replace("'json'::\"agentresponseformat\"", "json")
    }

    private companion object {
        val tables = setOf(
            "users",
            "developers",
            "agents",
            "agent_versions",
            "agent_dependencies",
            "execution_quotes",
            "executions",
            "execution_steps",
            "execution_events",
            "payment_attempts",
            "payment_settlement_journals",
            "revenue_entries"
        )
        val enums = setOf(
            "AgentVersionStatus",
            "AgentResponseFormat",
            "ExecutionStatus",
            "ExecutionStepStatus",
            "PaymentAttemptStatus",
            "PaymentMode",
            "RevenueType"
        )

        private fun c(table: String, vararg rows: String): Map<String, String> {
            return rows.associate {
                val (name, type, nullable, defaultValue) = it.split("|")
                "$table.$name" to "$type|$nullable|$defaultValue"
            }
        }

        val columns = buildMap {
            putAll(
                c(
                    "users",
                    "id|uuid|NO|",
                    "external_id|text|NO|",
                    "created_at|timestamp|NO|current_timestamp",
                    "updated_at|timestamp|NO|"
                )
            ); putAll(
            c(
                "developers",
                "id|uuid|NO|",
                "user_id|uuid|NO|",
                "display_name|text|NO|",
                "created_at|timestamp|NO|current_timestamp",
                "updated_at|timestamp|NO|"
            )
        ); putAll(
            c(
                "agents",
                "id|uuid|NO|",
                "developer_id|uuid|NO|",
                "slug|text|NO|",
                "name|text|NO|",
                "description|text|NO|",
                "created_at|timestamp|NO|current_timestamp",
                "updated_at|timestamp|NO|"
            )
        );
            putAll(
                c(
                    "agent_versions",
                    "id|uuid|NO|",
                    "agent_id|uuid|NO|",
                    "semver|text|NO|",
                    "status|AgentVersionStatus|NO|draft",
                    "endpoint|text|NO|",
                    "price_atomic|int8|NO|",
                    "network|text|NO|",
                    "asset|text|NO|",
                    "pay_to|text|NO|",
                    "response_format|AgentResponseFormat|NO|json",
                    "created_at|timestamp|NO|current_timestamp",
                    "updated_at|timestamp|NO|"
                )
            ); putAll(
            c(
                "agent_dependencies",
                "id|uuid|NO|",
                "source_version_id|uuid|NO|",
                "target_agent_id|uuid|NO|",
                "version_constraint|text|NO|",
                "required|bool|NO|true",
                "max_price_atomic|int8|NO|",
                "max_calls|int4|NO|1",
                "created_at|timestamp|NO|current_timestamp",
                "updated_at|timestamp|NO|"
            )
        ); putAll(
            c(
                "execution_quotes",
                "id|uuid|NO|",
                "root_version_id|uuid|NO|",
                "expires_at|timestamp|NO|",
                "max_cost_atomic|int8|NO|",
                "snapshot|jsonb|NO|",
                "created_at|timestamp|NO|current_timestamp"
            )
        );
            putAll(
                c(
                    "executions",
                    "id|uuid|NO|",
                    "quote_id|uuid|NO|",
                    "status|ExecutionStatus|NO|pending",
                    "max_budget_atomic|int8|NO|",
                    "reserved_cost_atomic|int8|NO|0",
                    "actual_cost_atomic|int8|NO|0",
                    "failure_code|text|YES|",
                    "created_at|timestamp|NO|current_timestamp",
                    "updated_at|timestamp|NO|",
                    "question|text|YES|",
                    "input|jsonb|YES|"
                )
            ); putAll(
            c(
                "execution_steps",
                "id|uuid|NO|",
                "execution_id|uuid|NO|",
                "parent_step_id|uuid|YES|",
                "agent_version_id|uuid|NO|",
                "status|ExecutionStepStatus|NO|created",
                "call_path|jsonb|NO|",
                "idempotency_key|text|YES|",
                "cost_atomic|int8|NO|0",
                "output|jsonb|YES|",
                "failure_code|text|YES|",
                "created_at|timestamp|NO|current_timestamp",
                "updated_at|timestamp|NO|",
                "request_fingerprint|text|YES|"
            )
        ); putAll(
            c(
                "execution_events",
                "id|uuid|NO|",
                "execution_id|uuid|NO|",
                "sequence|int4|NO|",
                "type|text|NO|",
                "payload|jsonb|NO|",
                "created_at|timestamp|NO|current_timestamp"
            )
        );
            putAll(
                c(
                    "payment_attempts",
                    "id|uuid|NO|",
                    "execution_step_id|uuid|NO|",
                    "status|PaymentAttemptStatus|NO|required",
                    "amount_atomic|int8|NO|",
                    "network|text|NO|",
                    "asset|text|NO|",
                    "pay_to|text|NO|",
                    "transaction_hash|text|YES|",
                    "failure_code|text|YES|",
                    "created_at|timestamp|NO|current_timestamp",
                    "updated_at|timestamp|NO|",
                    "payment_mode|PaymentMode|NO|simulated",
                    "payment_identifier|text|YES|"
                )
            ); putAll(
            c(
                "payment_settlement_journals",
                "id|uuid|NO|",
                "payment_attempt_id|uuid|NO|",
                "transaction_hash|text|NO|",
                "created_at|timestamp|NO|current_timestamp",
                "updated_at|timestamp|NO|"
            )
        ); putAll(
            c(
                "revenue_entries",
                "id|uuid|NO|",
                "developer_id|uuid|NO|",
                "execution_step_id|uuid|NO|",
                "payment_attempt_id|uuid|NO|",
                "type|RevenueType|NO|",
                "amount_atomic|int8|NO|",
                "payment_mode|PaymentMode|NO|",
                "transaction_hash|text|YES|",
                "payment_identifier|text|YES|",
                "created_at|timestamp|NO|current_timestamp"
            )
        )
        }
        val foreignKeys = setOf(
            "developers_user_id_fkey",
            "agents_developer_id_fkey",
            "agent_versions_agent_id_fkey",
            "agent_dependencies_source_version_id_fkey",
            "agent_dependencies_target_agent_id_fkey",
            "execution_quotes_root_version_id_fkey",
            "executions_quote_id_fkey",
            "execution_steps_execution_id_fkey",
            "execution_steps_parent_step_id_fkey",
            "execution_steps_agent_version_id_fkey",
            "execution_events_execution_id_fkey",
            "payment_attempts_execution_step_id_fkey",
            "payment_settlement_journals_payment_attempt_id_fkey",
            "revenue_entries_developer_id_fkey",
            "revenue_entries_execution_step_id_fkey",
            "revenue_entries_payment_attempt_id_fkey"
        )
        val checks = setOf("agent_dependencies_max_price_atomic_non_negative", "agent_dependencies_max_calls_range")
        val indexes = setOf(
            "users_external_id_key",
            "developers_user_id_key",
            "agents_slug_key",
            "agents_developer_id_idx",
            "agent_versions_agent_id_status_idx",
            "agent_versions_agent_id_semver_key",
            "agent_dependencies_source_version_id_target_agent_id_key",
            "agent_dependencies_source_version_id_idx",
            "agent_dependencies_target_agent_id_idx",
            "execution_quotes_root_version_id_expires_at_idx",
            "execution_steps_parent_step_id_idempotency_key_key",
            "executions_status_created_at_idx",
            "executions_quote_id_idx",
            "execution_steps_execution_id_created_at_idx",
            "execution_steps_parent_step_id_idx",
            "execution_events_execution_id_sequence_key",
            "execution_events_execution_id_sequence_idx",
            "payment_attempts_execution_step_id_created_at_idx",
            "payment_settlement_journals_payment_attempt_id_key",
            "revenue_entries_payment_attempt_id_key",
            "revenue_entries_transaction_hash_key",
            "revenue_entries_payment_identifier_key",
            "revenue_entries_developer_id_created_at_id_idx"
        )
    }
}
