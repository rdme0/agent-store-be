package com.agentstore.support

import java.math.BigInteger
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate

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

data class CapabilityMarketplaceRegistryFixture(
    val capabilityId: UUID,
    val rootSlug: String,
    val rootVersionId: UUID,
    val selectedProviderVersionId: UUID,
    val excludedProviderVersionId: UUID,
    val directProviderVersionId: UUID,
    val excludedProviderAgentId: UUID,
    val rootDeveloperId: UUID,
    val selectedProviderDeveloperId: UUID,
    val excludedProviderDeveloperId: UUID,
    val directProviderDeveloperId: UUID,
    val payTos: Set<String>,
)

/** Creates a minimal, fully tracked registry → quote → execution chain for opt-in PostgreSQL tests. */
class PostgresRuntimeFixture(
    private val jdbcTemplate: JdbcTemplate,
    private val cleaner: PostgresFixtureCleaner,
) {
    fun createCapabilityMarketplaceRegistry(): CapabilityMarketplaceRegistryFixture {
        val capabilityId = UUID.randomUUID()
        val root = createRegistryVersion(
            slug = "reference-investment-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8090/agents/investment/invoke",
            priceAtomic = 1_000,
            payTo = "0x0000000000000000000000000000000000000001",
            capabilityId = null,
        )
        val newsFast = createRegistryVersion(
            slug = "reference-news-fast-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8092/agents/news-fast/invoke",
            priceAtomic = 900,
            payTo = "0x0000000000000000000000000000000000000004",
            capabilityId = null,
        )
        val newsDeep = createRegistryVersion(
            slug = "reference-news-deep-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8093/agents/news-deep/invoke",
            priceAtomic = 1_500,
            payTo = "0x0000000000000000000000000000000000000005",
            capabilityId = null,
        )
        val risk = createRegistryVersion(
            slug = "reference-risk-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8094/agents/risk/invoke",
            priceAtomic = 1_000,
            payTo = "0x0000000000000000000000000000000000000006",
            capabilityId = null,
        )

        jdbcTemplate.update(
            """
            insert into agent_capabilities
                (id, key, contract_version, name, description, response_format, input_schema, output_schema)
            values (?, ?, '1.0.0', 'Stock news analysis', 'Reference news provider contract',
                'JSON'::"AgentResponseFormat", '{"type":"object"}'::jsonb, '{"type":"object"}'::jsonb)
            """.trimIndent(),
            capabilityId,
            "finance.stock-news-analysis.$capabilityId",
        )
        cleaner.trackCapability(capabilityId)

        listOf(newsFast, newsDeep).forEach { provider ->
            jdbcTemplate.update(
                "update agent_versions set capability_id = ? where id = ?",
                capabilityId,
                provider.versionId,
            )
        }

        createCapabilityDependency(
            sourceVersionId = root.versionId,
            capabilityId = capabilityId,
        )
        createDirectDependency(
            sourceVersionId = root.versionId,
            targetAgentId = risk.agentId,
        )

        return CapabilityMarketplaceRegistryFixture(
            capabilityId = capabilityId,
            rootSlug = root.slug,
            rootVersionId = root.versionId,
            selectedProviderVersionId = newsFast.versionId,
            excludedProviderVersionId = newsDeep.versionId,
            directProviderVersionId = risk.versionId,
            excludedProviderAgentId = newsDeep.agentId,
            rootDeveloperId = root.developerId,
            selectedProviderDeveloperId = newsFast.developerId,
            excludedProviderDeveloperId = newsDeep.developerId,
            directProviderDeveloperId = risk.developerId,
            payTos = setOf(root.payTo, newsFast.payTo, newsDeep.payTo, risk.payTo),
        )
    }

    fun publishMorePreferredProvider(agentId: UUID, capabilityId: UUID): UUID {
        val versionId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, capability_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, ?, '2.0.0', 'ACTIVE'::\"AgentVersionStatus\", 'http://127.0.0.1:8093/agents/news-deep/invoke', 100, 'eip155:84532', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', '0x0000000000000000000000000000000000000005', 'JSON'::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            versionId,
            agentId,
            capabilityId,
        )
        cleaner.trackAgentVersion(versionId)
        return versionId
    }

    fun createExecutionFromSnapshot(
        rootVersionId: UUID,
        rootSlug: String,
        maxBudget: BigInteger,
        snapshot: String,
    ): RuntimeFixture {
        val quoteId = UUID.randomUUID()
        val executionId = UUID.randomUUID()
        val rootStepId = UUID.randomUUID()
        val developerId = jdbcTemplate.queryForObject(
            """
            select agent.developer_id
            from agents agent
            join agent_versions version on version.agent_id = agent.id
            where version.id = ?
            """.trimIndent(),
            UUID::class.java,
            rootVersionId,
        ) ?: error("Reference root developer was not found")

        jdbcTemplate.update(
            "insert into execution_quotes (id, root_version_id, expires_at, max_cost_atomic, snapshot, created_at, updated_at) values (?, ?, current_timestamp + interval '5 minutes', ?, ?::jsonb, current_timestamp, current_timestamp)",
            quoteId,
            rootVersionId,
            maxBudget,
            snapshot,
        )
        cleaner.trackQuote(quoteId)
        jdbcTemplate.update(
            "insert into executions (id, quote_id, status, max_budget_atomic, reserved_cost_atomic, actual_cost_atomic, created_at, updated_at) values (?, ?, 'PENDING'::\"ExecutionStatus\", ?, 0, 0, current_timestamp, current_timestamp)",
            executionId,
            quoteId,
            maxBudget,
        )
        cleaner.trackExecution(executionId)
        jdbcTemplate.update(
            "insert into execution_steps (id, execution_id, agent_version_id, status, call_path, cost_atomic, created_at, updated_at) values (?, ?, ?, 'PAYMENT_REQUIRED'::\"ExecutionStepStatus\", ?::jsonb, 0, current_timestamp, current_timestamp)",
            rootStepId,
            executionId,
            rootVersionId,
            "[\"$rootSlug\"]",
        )
        cleaner.trackStep(rootStepId)

        return RuntimeFixture(
            developerId = developerId,
            agentVersionId = rootVersionId,
            quoteId = quoteId,
            executionId = executionId,
            rootStepId = rootStepId,
        )
    }

    fun attachCapability(
        quoteId: UUID,
        versionId: UUID,
        inputSchema: String = """{"type":"object"}""",
        outputSchema: String = """{"type":"object"}""",
    ): UUID {
        val capabilityId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into agent_capabilities
                (id, key, contract_version, name, description, response_format, input_schema, output_schema)
            select ?, ?, '1.0.0', 'Test capability', 'Test capability', response_format, ?::jsonb, ?::jsonb
            from agent_versions where id = ?
            """.trimIndent(),
            capabilityId,
            "test.runtime.$capabilityId",
            inputSchema,
            outputSchema,
            versionId,
        )
        cleaner.trackCapability(capabilityId)
        jdbcTemplate.update("update agent_versions set capability_id = ? where id = ?", capabilityId, versionId)
        val capabilitySnapshot = """
            {
              "id":"$capabilityId",
              "key":"test.runtime.$capabilityId",
              "contractVersion":"1.0.0",
              "inputSchema":$inputSchema,
              "outputSchema":$outputSchema
            }
        """.trimIndent()
        val rootVersionId = jdbcTemplate.queryForObject(
            "select snapshot #>> '{version,id}' from execution_quotes where id = ?",
            String::class.java,
            quoteId,
        )
        val path = if (rootVersionId == versionId.toString()) {
            "{version,functionContract}"
        } else {
            "{dependencies,0,resolved,version,functionContract}"
        }
        jdbcTemplate.update(
            "update execution_quotes set snapshot = jsonb_set(snapshot, ?::text[], ?::jsonb, true) where id = ?",
            path,
            capabilitySnapshot,
            quoteId,
        )
        return capabilityId
    }

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
            "insert into agents (id, developer_id, slug, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
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
        dependencyCount: Int = 1,
    ): DependencyRuntimeFixture {
        require(dependencyCount >= 1) { "dependencyCount must be at least one" }
        val root = create(maxBudget, responseFormat = rootResponseFormat)
        val rootSlug = "root-${root.agentVersionId}"
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
        val children = (1..dependencyCount).map { index ->
            val childSlug = "child-${UUID.randomUUID()}"
            val childAgentId = UUID.randomUUID()
            val childVersionId = UUID.randomUUID()
            jdbcTemplate.update(
                "insert into agents (id, developer_id, slug, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
                childAgentId,
                root.developerId,
                childSlug,
                "fixture child $index",
                "fixture child $index",
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
            childSlug to childVersionId
        }
        val childSlug = children.first().first
        val childVersionId = children.first().second
        val dependencies = children.joinToString(separator = ",") { (slug, versionId) ->
            """{"maxPriceAtomic":"1","resolved":{"version":{"id":"$versionId","agentSlug":"$slug","endpoint":"http://fixture/child","priceAtomic":"1","network":"eip155:84532","asset":"USDC","payTo":"$receiver","responseFormat":"$childResponseFormat"},"dependencies":[]}}"""
        }
        val snapshot = """
            {"version":{"id":"${root.agentVersionId}","agentSlug":"$rootSlug","endpoint":"http://fixture/root","priceAtomic":"1","network":"eip155:84532","asset":"USDC","payTo":"$receiver","responseFormat":"$rootResponseFormat"},"dependencies":[$dependencies]}
        """.trimIndent()
        jdbcTemplate.update(
            "update execution_quotes set snapshot = ?::jsonb, max_cost_atomic = ? where id = ?",
            snapshot,
            BigInteger.valueOf((dependencyCount + 1).toLong()),
            root.quoteId
        )
        jdbcTemplate.update(
            "update executions set max_budget_atomic = ? where id = ?",
            BigInteger.valueOf((dependencyCount + 1).toLong()),
            root.executionId
        )
        return DependencyRuntimeFixture(root, rootSlug, childSlug, childVersionId)
    }

    private fun createRegistryVersion(
        slug: String,
        endpoint: String,
        priceAtomic: Long,
        payTo: String,
        capabilityId: UUID?,
    ): RegistryVersionFixture {
        val userId = cleaner.createStandaloneUser()
        val developerId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into developers (id, user_id, display_name, created_at, updated_at) values (?, ?, ?, current_timestamp, current_timestamp)",
            developerId,
            userId,
            "reference-provider-$developerId",
        )
        cleaner.trackDeveloper(developerId)
        jdbcTemplate.update(
            "insert into agents (id, developer_id, slug, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
            agentId,
            developerId,
            slug,
            slug,
            "Capability marketplace reference provider",
        )
        cleaner.trackAgent(agentId)
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, capability_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, ?, '1.0.0', 'ACTIVE'::\"AgentVersionStatus\", ?, ?, 'eip155:84532', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', ?, 'JSON'::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            versionId,
            agentId,
            capabilityId,
            endpoint,
            priceAtomic,
            payTo,
        )
        cleaner.trackAgentVersion(versionId)

        return RegistryVersionFixture(
            developerId = developerId,
            agentId = agentId,
            versionId = versionId,
            slug = slug,
            payTo = payTo,
        )
    }

    private fun createCapabilityDependency(sourceVersionId: UUID, capabilityId: UUID) {
        val dependencyId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into agent_dependencies (id, source_version_id, function_contract_id, provider_scope, selection_strategy, exploration_percent, version_constraint, required, max_price_atomic, max_calls, created_at, updated_at) values (?, ?, ?, 'MARKETPLACE'::\"ProviderScope\", 'LOWEST_PRICE'::\"ProviderSelectionStrategy\", 0, '*', true, 2000, 1, current_timestamp, current_timestamp)",
            dependencyId,
            sourceVersionId,
            capabilityId,
        )
        cleaner.trackDependency(dependencyId)
    }

    private fun createDirectDependency(sourceVersionId: UUID, targetAgentId: UUID) {
        val dependencyId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into agent_dependencies (id, source_version_id, target_agent_id, version_constraint, required, max_price_atomic, max_calls, created_at, updated_at) values (?, ?, ?, '*', true, 2000, 1, current_timestamp, current_timestamp)",
            dependencyId,
            sourceVersionId,
            targetAgentId,
        )
        cleaner.trackDependency(dependencyId)
    }

    private data class RegistryVersionFixture(
        val developerId: UUID,
        val agentId: UUID,
        val versionId: UUID,
        val slug: String,
        val payTo: String,
    )
}
