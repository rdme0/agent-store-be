package com.agentstore.support

import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigInteger
import java.util.*

data class RuntimeFixture(
    val developerId: UUID,
    val agentVersionId: UUID,
    val quoteId: UUID,
    val executionId: UUID,
    val rootStepId: UUID,
)

data class DependencyRuntimeFixture(
    val root: RuntimeFixture,
    val rootSlug: String,
    val childSlug: String,
    val childVersionId: UUID,
)

/** Creates a minimal, fully tracked registry → quote → execution chain for opt-in PostgreSQL tests. */
class PostgresRuntimeFixture(
    private val jdbcTemplate: JdbcTemplate,
    private val cleaner: PostgresFixtureCleaner,
) {
    fun create(
        maxBudget: BigInteger = BigInteger.TEN,
        executionStatus: String = "PENDING",
        responseFormat: String = "JSON",
    ): RuntimeFixture {
        val userId = cleaner.createStandaloneUser()
        val developerId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        val quoteId = UUID.randomUUID()
        val executionId = UUID.randomUUID()
        val rootStepId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into developers (id, user_id, display_name, created_at, updated_at) values (?, ?, ?, current_timestamp, current_timestamp)",
            developerId,
            userId,
            "postgres-integration-$developerId"
        )
        cleaner.trackDeveloper(developerId)
        jdbcTemplate.update(
            "insert into agents (id, developer_id, slug, name, description, created_at, updated_at) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)",
            agentId,
            developerId,
            "postgres-integration-$agentId",
            "fixture",
            "fixture"
        )
        cleaner.trackAgent(agentId)
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, '1.0.0', 'ACTIVE'::\"AgentVersionStatus\", 'http://127.0.0.1:9', 1, 'eip155:84532', 'USDC', '0x0000000000000000000000000000000000000001', ?::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            versionId,
            agentId,
            responseFormat,
        )
        cleaner.trackAgentVersion(versionId)
        jdbcTemplate.update(
            "insert into execution_quotes (id, root_version_id, expires_at, max_cost_atomic, snapshot, created_at, updated_at) values (?, ?, current_timestamp + interval '5 minutes', ?, '{}'::jsonb, current_timestamp, current_timestamp)",
            quoteId,
            versionId,
            maxBudget
        )
        cleaner.trackQuote(quoteId)
        jdbcTemplate.update(
            "insert into executions (id, quote_id, status, max_budget_atomic, reserved_cost_atomic, actual_cost_atomic, created_at, updated_at) values (?, ?, ?::\"ExecutionStatus\", ?, 0, 0, current_timestamp, current_timestamp)",
            executionId,
            quoteId,
            executionStatus,
            maxBudget
        )
        cleaner.trackExecution(executionId)
        jdbcTemplate.update(
            "insert into execution_steps (id, execution_id, agent_version_id, status, call_path, cost_atomic, created_at, updated_at) values (?, ?, ?, 'PAYMENT_REQUIRED'::\"ExecutionStepStatus\", '[\"fixture\"]'::jsonb, 0, current_timestamp, current_timestamp)",
            rootStepId,
            executionId,
            versionId
        )
        cleaner.trackStep(rootStepId)
        return RuntimeFixture(developerId, versionId, quoteId, executionId, rootStepId)
    }

    fun createRootWithDependency(
        maxBudget: BigInteger = BigInteger.TEN,
        rootResponseFormat: String = "JSON",
        childResponseFormat: String = "JSON",
    ): DependencyRuntimeFixture {
        val root = create(maxBudget, responseFormat = rootResponseFormat)
        val rootSlug = "root-${root.agentVersionId}"
        val childSlug = "child-${UUID.randomUUID()}"
        val childAgentId = UUID.randomUUID()
        val childVersionId = UUID.randomUUID()
        val receiver = "0x0000000000000000000000000000000000000001"

        jdbcTemplate.update(
            "update agents set slug = ? where id = (select agent_id from agent_versions where id = ?)",
            rootSlug,
            root.agentVersionId
        )
        jdbcTemplate.update(
            "update agent_versions set endpoint = 'http://fixture/root' where id = ?",
            root.agentVersionId
        )
        jdbcTemplate.update(
            "update execution_steps set call_path = ?::jsonb where id = ?",
            "[\"$rootSlug\"]",
            root.rootStepId
        )
        jdbcTemplate.update(
            "insert into agents (id, developer_id, slug, name, description, created_at, updated_at) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)",
            childAgentId,
            root.developerId,
            childSlug,
            "fixture child",
            "fixture child"
        )
        cleaner.trackAgent(childAgentId)
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, '1.0.0', 'ACTIVE'::\"AgentVersionStatus\", 'http://fixture/child', 1, 'eip155:84532', 'USDC', ?, ?::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            childVersionId,
            childAgentId,
            receiver,
            childResponseFormat,
        )
        cleaner.trackAgentVersion(childVersionId)
        val snapshot = """
            {"version":{"id":"${root.agentVersionId}","agentSlug":"$rootSlug","endpoint":"http://fixture/root","priceAtomic":"1","network":"eip155:84532","asset":"USDC","payTo":"$receiver","responseFormat":"$rootResponseFormat"},"dependencies":[{"maxPriceAtomic":"1","resolved":{"version":{"id":"$childVersionId","agentSlug":"$childSlug","endpoint":"http://fixture/child","priceAtomic":"1","network":"eip155:84532","asset":"USDC","payTo":"$receiver","responseFormat":"$childResponseFormat"},"dependencies":[]}}]}
        """.trimIndent()
        jdbcTemplate.update(
            "update execution_quotes set snapshot = ?::jsonb, max_cost_atomic = ? where id = ?",
            snapshot,
            BigInteger.valueOf(2),
            root.quoteId
        )
        jdbcTemplate.update(
            "update executions set max_budget_atomic = ? where id = ?",
            BigInteger.valueOf(2),
            root.executionId
        )
        return DependencyRuntimeFixture(root, rootSlug, childSlug, childVersionId)
    }
}
