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
    val rootCode: String,
    val childCode: String,
    val childVersionId: UUID,
)

data class FunctionContractMarketplaceRegistryFixture(
    val functionContractId: UUID,
    val rootCode: String,
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
    fun createFunctionContractMarketplaceRegistry(): FunctionContractMarketplaceRegistryFixture {
        val functionContractId = UUID.randomUUID()
        val root = createRegistryVersion(
            code = "reference-investment-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8090/agents/investment/invoke",
            priceAtomic = 1_000,
            payTo = "0x0000000000000000000000000000000000000001",
            functionContractId = null,
        )
        val newsFast = createRegistryVersion(
            code = "reference-news-fast-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8092/agents/news-fast/invoke",
            priceAtomic = 900,
            payTo = "0x0000000000000000000000000000000000000004",
            functionContractId = null,
        )
        val newsDeep = createRegistryVersion(
            code = "reference-news-deep-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8093/agents/news-deep/invoke",
            priceAtomic = 1_500,
            payTo = "0x0000000000000000000000000000000000000005",
            functionContractId = null,
        )
        val risk = createRegistryVersion(
            code = "reference-risk-${UUID.randomUUID()}",
            endpoint = "http://127.0.0.1:8094/agents/risk/invoke",
            priceAtomic = 1_000,
            payTo = "0x0000000000000000000000000000000000000006",
            functionContractId = null,
        )

        jdbcTemplate.update(
            """
            insert into function_contracts
                (id, code, contract_version, name, description, response_format, input_schema, output_schema)
            values (?, ?, '1.0.0', 'Stock news analysis', 'Reference news provider contract',
                'JSON'::"AgentResponseFormat", '{"type":"object"}'::jsonb, '{"type":"object"}'::jsonb)
            """.trimIndent(),
            functionContractId,
            "finance.stock-news-analysis.$functionContractId",
        )
        cleaner.trackFunctionContract(functionContractId)

        listOf(newsFast, newsDeep).forEach { provider ->
            jdbcTemplate.update(
                "update agent_versions set function_contract_id = ? where id = ?",
                functionContractId,
                provider.versionId,
            )
        }

        createFunctionContractDependency(
            sourceVersionId = root.versionId,
            functionContractId = functionContractId,
        )
        createDirectDependency(
            sourceVersionId = root.versionId,
            targetAgentId = risk.agentId,
        )

        return FunctionContractMarketplaceRegistryFixture(
            functionContractId = functionContractId,
            rootCode = root.code,
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

    fun publishMorePreferredProvider(agentId: UUID, functionContractId: UUID): UUID {
        val versionId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, function_contract_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, ?, '2.0.0', 'ACTIVE'::\"AgentVersionStatus\", 'http://127.0.0.1:8093/agents/news-deep/invoke', 100, 'eip155:84532', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', '0x0000000000000000000000000000000000000005', 'JSON'::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            versionId,
            agentId,
            functionContractId,
        )
        cleaner.trackAgentVersion(versionId)
        return versionId
    }

    fun createExecutionFromSnapshot(
        rootVersionId: UUID,
        rootCode: String,
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
            "[\"$rootCode\"]",
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

    fun attachFunctionContract(
        quoteId: UUID,
        versionId: UUID,
        inputSchema: String = """{"type":"object"}""",
        outputSchema: String = """{"type":"object"}""",
    ): UUID {
        val functionContractId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into function_contracts
                (id, code, contract_version, name, description, response_format, input_schema, output_schema)
            select ?, ?, '1.0.0', 'Test function contract', 'Test function contract', response_format, ?::jsonb, ?::jsonb
            from agent_versions where id = ?
            """.trimIndent(),
            functionContractId,
            "test.runtime.$functionContractId",
            inputSchema,
            outputSchema,
            versionId,
        )
        cleaner.trackFunctionContract(functionContractId)
        jdbcTemplate.update("update agent_versions set function_contract_id = ? where id = ?", functionContractId, versionId)
        val functionContractSnapshot = """
            {
              "id":"$functionContractId",
              "code":"test.runtime.$functionContractId",
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
            functionContractSnapshot,
            quoteId,
        )
        return functionContractId
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
            "insert into agents (id, developer_id, code, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
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
        val rootCode = "root-${root.agentVersionId}"
        val receiver = "0x0000000000000000000000000000000000000001"

        jdbcTemplate.update(
            "update agents set code = ? where id = (select agent_id from agent_versions where id = ?)",
            rootCode,
            root.agentVersionId
        )
        jdbcTemplate.update(
            "update agent_versions set endpoint = 'http://fixture/root' where id = ?",
            root.agentVersionId
        )
        jdbcTemplate.update(
            "update execution_steps set call_path = ?::jsonb where id = ?",
            "[\"$rootCode\"]",
            root.rootStepId
        )
        val children = (1..dependencyCount).map { index ->
            val childCode = "child-${UUID.randomUUID()}"
            val childAgentId = UUID.randomUUID()
            val childVersionId = UUID.randomUUID()
            jdbcTemplate.update(
                "insert into agents (id, developer_id, code, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
                childAgentId,
                root.developerId,
                childCode,
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
            childCode to childVersionId
        }
        val childCode = children.first().first
        val childVersionId = children.first().second
        val dependencies = children.joinToString(separator = ",") { (code, versionId) ->
            """{"maxPriceAtomic":"1","resolved":{"version":{"id":"$versionId","agentCode":"$code","endpoint":"http://fixture/child","priceAtomic":"1","network":"eip155:84532","asset":"USDC","payTo":"$receiver","responseFormat":"$childResponseFormat"},"dependencies":[]}}"""
        }
        val snapshot = """
            {"version":{"id":"${root.agentVersionId}","agentCode":"$rootCode","endpoint":"http://fixture/root","priceAtomic":"1","network":"eip155:84532","asset":"USDC","payTo":"$receiver","responseFormat":"$rootResponseFormat"},"dependencies":[$dependencies]}
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
        return DependencyRuntimeFixture(root, rootCode, childCode, childVersionId)
    }

    private fun createRegistryVersion(
        code: String,
        endpoint: String,
        priceAtomic: Long,
        payTo: String,
        functionContractId: UUID?,
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
            "insert into agents (id, developer_id, code, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
            agentId,
            developerId,
            code,
            code,
            "Function contract marketplace reference provider",
        )
        cleaner.trackAgent(agentId)
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, function_contract_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, ?, '1.0.0', 'ACTIVE'::\"AgentVersionStatus\", ?, ?, 'eip155:84532', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', ?, 'JSON'::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            versionId,
            agentId,
            functionContractId,
            endpoint,
            priceAtomic,
            payTo,
        )
        cleaner.trackAgentVersion(versionId)

        return RegistryVersionFixture(
            developerId = developerId,
            agentId = agentId,
            versionId = versionId,
            code = code,
            payTo = payTo,
        )
    }

    private fun createFunctionContractDependency(sourceVersionId: UUID, functionContractId: UUID) {
        val dependencyId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into agent_dependencies (id, source_version_id, function_contract_id, provider_scope, selection_strategy, version_constraint, required, max_price_atomic, max_calls, created_at, updated_at) values (?, ?, ?, 'MARKETPLACE'::\"ProviderScope\", 'LOWEST_PRICE'::\"ProviderSelectionStrategy\", '*', true, 2000, 1, current_timestamp, current_timestamp)",
            dependencyId,
            sourceVersionId,
            functionContractId,
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
        val code: String,
        val payTo: String,
    )
}
