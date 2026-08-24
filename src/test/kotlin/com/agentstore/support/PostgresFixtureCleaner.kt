package com.agentstore.support

import java.util.LinkedHashSet
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate

/** Tracks only rows created by an integration test; cleanup never uses broad table deletes. */
class PostgresFixtureCleaner(private val jdbcTemplate: JdbcTemplate) {
    private val userIds = linkedSetOf<UUID>()
    private val developerIds = linkedSetOf<UUID>()
    private val agentIds = linkedSetOf<UUID>()
    private val agentVersionIds = linkedSetOf<UUID>()
    private val capabilityIds = linkedSetOf<UUID>()
    private val dependencyIds = linkedSetOf<UUID>()
    private val quoteIds = linkedSetOf<UUID>()
    private val executionIds = linkedSetOf<UUID>()
    private val stepIds = linkedSetOf<UUID>()
    private val eventIds = linkedSetOf<UUID>()
    private val paymentAttemptIds = linkedSetOf<UUID>()
    private val paymentJournalIds = linkedSetOf<UUID>()
    private val revenueEntryIds = linkedSetOf<UUID>()

    fun createStandaloneUser(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into users (id, external_id, created_at, updated_at) values (?, ?, current_timestamp, current_timestamp)",
            id,
            "postgres-integration-$id",
        )
        userIds += id
        return id
    }

    fun trackDeveloper(id: UUID): Boolean {
        return developerIds.add(id)
    }

    fun trackAgent(id: UUID): Boolean {
        return agentIds.add(id)
    }

    fun trackAgentVersion(id: UUID): Boolean {
        return agentVersionIds.add(id)
    }

    fun trackCapability(id: UUID): Boolean {
        return capabilityIds.add(id)
    }

    fun trackDependency(id: UUID): Boolean {
        return dependencyIds.add(id)
    }

    fun trackQuote(id: UUID): Boolean {
        return quoteIds.add(id)
    }

    fun trackExecution(id: UUID): Boolean {
        return executionIds.add(id)
    }

    fun trackStep(id: UUID): Boolean {
        return stepIds.add(id)
    }

    fun trackEvent(id: UUID): Boolean {
        return eventIds.add(id)
    }

    fun trackPaymentAttempt(id: UUID): Boolean {
        return paymentAttemptIds.add(id)
    }

    fun trackPaymentJournal(id: UUID): Boolean {
        return paymentJournalIds.add(id)
    }

    fun trackRevenueEntry(id: UUID): Boolean {
        return revenueEntryIds.add(id)
    }

    fun cleanup() {
        // A test may create revenue through the real settlement path; its generated
        // UUID is not known to the fixture builder, but it is still scoped to a
        // tracked execution step and therefore safe to remove first.
        executionIds.reversed().forEach { executionId ->
            jdbcTemplate.update(
                "delete from agent_invocation_observations where execution_step_id in (select id from execution_steps where execution_id = ?)",
                executionId,
            )
            jdbcTemplate.update(
                "delete from revenue_entries where execution_step_id in (select id from execution_steps where execution_id = ?)",
                executionId,
            )
        }
        stepIds.reversed()
            .forEach { id ->
                jdbcTemplate.update(
                    "delete from agent_invocation_observations where execution_step_id = ?",
                    id,
                )
                jdbcTemplate.update(
                    "delete from revenue_entries where execution_step_id = ?",
                    id
                )
            }
        deleteTracked("revenue_entries", revenueEntryIds)
        deleteTracked("payment_settlement_journals", paymentJournalIds)
        deleteTracked("payment_attempts", paymentAttemptIds)
        deleteTracked("execution_events", eventIds)
        deleteTracked("execution_steps", stepIds)
        deleteTracked("executions", executionIds)
        deleteTracked("execution_quotes", quoteIds)
        deleteTracked("agent_dependencies", dependencyIds)
        deleteTracked("agent_versions", agentVersionIds)
        deleteTracked("agent_capabilities", capabilityIds)
        deleteTracked("agents", agentIds)
        deleteTracked("developers", developerIds)
        deleteTracked("users", userIds)
    }

    private fun deleteTracked(table: String, ids: LinkedHashSet<UUID>) {
        ids.reversed().forEach { id -> jdbcTemplate.update("delete from $table where id = ?", id) }
        ids.clear()
    }
}
