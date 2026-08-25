package com.agentstore.common.migration

import com.agentstore.agent.dto.request.AgentManifestRequest
import com.agentstore.agent.service.AgentManifestService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.dto.request.UpdateDependencyRequest
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.service.DependencyService
import com.agentstore.support.PostgresIntegrationTestSupport
import java.sql.Connection
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
class PostgresSchemaIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var manifestService: AgentManifestService

    @Autowired
    private lateinit var dependencyService: DependencyService

    @Test
    fun `flyway applies every current migration`() {
        val version = jdbcTemplate.queryForObject(
            "select version from flyway_schema_history where success = true order by installed_rank desc limit 1",
            String::class.java,
        )
        assertEquals("22", version)
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'agents' and column_name = 'code'",
                Int::class.java,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'agents' and column_name = 'slug'",
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'public' and tablename = 'agents' and indexname = 'agents_code_key'",
                Int::class.java,
            ),
        )
        val timestampColumns = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns where table_schema = 'public' and table_name in ('execution_quotes', 'revenue_entries', 'execution_events') and column_name = 'updated_at'",
            Int::class.java,
        )
        assertEquals(3, timestampColumns)
        assertEquals(
            1, jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'payment_attempts' and column_name = 'projected_at'",
                Int::class.java,
            )
        )
        val dependencyProviderConstraint = jdbcTemplate.queryForObject(
            """
            select pg_get_constraintdef(oid)
            from pg_constraint
            where conname = 'agent_dependencies_provider_model_check'
            """.trimIndent(),
            String::class.java,
        )
        requireNotNull(dependencyProviderConstraint)
        assertEquals(true, dependencyProviderConstraint.contains("function_contract_id IS NOT NULL"))
        assertEquals(false, dependencyProviderConstraint.contains("target_capability_id"))
        assertEquals(false, dependencyProviderConstraint.contains("selection_policy"))
        assertEquals(true, dependencyProviderConstraint.contains("provider_scope IS NOT NULL"))
        assertEquals(true, dependencyProviderConstraint.contains("exploration_percent"))
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'agent_dependency_allowed_providers' and column_name = 'updated_at'",
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'external_invocation_intents'",
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from pg_type where typname = 'ExternalInvocationStatus'",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `V21 preserves existing agent code data while renaming the unique index`() {
        val schema = "v21_agent_code_${UUID.randomUUID().toString().replace("-", "")}"
        val migration = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V21__20260825000000_rename_agent_slug_to_code.sql"),
        ).readText()
        jdbcTemplate.execute("create schema $schema")
        val dataSource = requireNotNull(jdbcTemplate.dataSource)
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    try {
                        statement.execute("create table $schema.agents (id uuid primary key, slug varchar(120) not null)")
                        statement.execute("create unique index agents_slug_key on $schema.agents (slug)")
                        statement.execute("insert into $schema.agents (id, slug) values ('${UUID.randomUUID()}', 'legacy-agent')")
                        statement.execute("set search_path to $schema")
                        statement.execute(migration)
                        statement.executeQuery("select code from agents where code = 'legacy-agent'").use { result ->
                            check(result.next())
                            assertEquals("legacy-agent", result.getString("code"))
                        }
                        statement.executeQuery(
                            "select indexname from pg_indexes where schemaname = '$schema' and tablename = 'agents'",
                        ).use { result ->
                            val indexNames = generateSequence {
                                if (result.next()) result.getString("indexname") else null
                            }.toSet()
                            assertEquals(true, indexNames.contains("agents_code_key"))
                            assertEquals(false, indexNames.contains("agents_slug_key"))
                        }
                    } finally {
                        statement.execute("set search_path to public")
                    }
                }
            }
        } finally {
            jdbcTemplate.execute("drop schema if exists $schema cascade")
        }
    }

    @Test
    fun `V22 converts every legacy constraint without changing its SemVer range`() {
        val schema = "v22_version_constraint_${UUID.randomUUID().toString().replace("-", "")}"
        val migration = requireNotNull(
            javaClass.classLoader.getResource(
                "db/migration/V22__20260825010000_convert_version_constraints_to_comparators.sql",
            ),
        ).readText()
        jdbcTemplate.execute("create schema $schema")
        val dataSource = requireNotNull(jdbcTemplate.dataSource)
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    try {
                        statement.execute(
                            "create table $schema.agent_dependencies (id integer primary key, version_constraint text not null)",
                        )
                        statement.execute(
                            "insert into $schema.agent_dependencies (id, version_constraint) values " +
                                "(1, '*'), (2, '1.2.3'), (3, '^1.2.3'), (4, '^0.2.3'), " +
                                "(5, '^0.0.3'), (6, '~1.2.3')",
                        )
                        statement.execute("set search_path to $schema")
                        statement.execute(migration)
                        statement.executeQuery(
                            "select id, version_constraint from agent_dependencies order by id",
                        ).use { result ->
                            val constraints = mutableMapOf<Int, String>()
                            while (result.next()) {
                                constraints[result.getInt("id")] = result.getString("version_constraint")
                            }
                            assertEquals(
                                mapOf(
                                    1 to "*",
                                    2 to "==1.2.3",
                                    3 to ">=1.2.3,<2.0.0",
                                    4 to ">=0.2.3,<0.3.0",
                                    5 to ">=0.0.3,<0.0.4",
                                    6 to ">=1.2.3,<1.3.0",
                                ),
                                constraints,
                            )
                        }
                    } finally {
                        statement.execute("set search_path to public")
                    }
                }
            }
        } finally {
            jdbcTemplate.execute("drop schema if exists $schema cascade")
        }
    }

    @Test
    fun `fixture cleanup deletes only the registered standalone user`() {
        val fixtureId = fixtureCleaner.createStandaloneUser()
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?",
                Int::class.java,
                fixtureId
            )
        )

        fixtureCleaner.cleanup()

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?",
                Int::class.java,
                fixtureId
            )
        )
    }

    @Test
    fun `capability key and contract version are unique under concurrent ownership boundary`() {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val key = "test.concurrent.${UUID.randomUUID()}"
        try {
            insertCapability(id = firstId, key = key)

            assertThrows(DataIntegrityViolationException::class.java) {
                insertCapability(id = secondId, key = key)
            }
        } finally {
            jdbcTemplate.update("delete from agent_capabilities where id in (?, ?)", firstId, secondId)
        }
    }

    @Test
    fun `external invocation idempotency lock serializes the same key`() {
        val dataSource = requireNotNull(jdbcTemplate.dataSource)
        dataSource.connection.use { first ->
            first.autoCommit = false
            assertEquals(true, tryAdvisoryLock(connection = first, key = "external-idempotency-lock"))

            dataSource.connection.use { second ->
                second.autoCommit = false
                assertEquals(false, tryAdvisoryLock(connection = second, key = "external-idempotency-lock"))
                second.rollback()
            }

            first.commit()
        }

        dataSource.connection.use { third ->
            third.autoCommit = false
            assertEquals(true, tryAdvisoryLock(connection = third, key = "external-idempotency-lock"))
            third.rollback()
        }
    }

    @Test
    fun `function marketplace dependency cannot retain a pinned agent target`() {
        val registry = runtimeFixture.createCapabilityMarketplaceRegistry()

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into agent_dependencies
                    (id, source_version_id, target_agent_id, function_contract_id, provider_scope,
                        selection_strategy, exploration_percent, version_constraint, required,
                        max_price_atomic, max_calls, created_at, updated_at)
                values (?, ?, ?, ?, 'MARKETPLACE'::"ProviderScope",
                    'LOWEST_PRICE'::"ProviderSelectionStrategy", 0, '*', true,
                    1000, 1, current_timestamp, current_timestamp)
                """.trimIndent(),
                UUID.randomUUID(),
                registry.rootVersionId,
                registry.excludedProviderAgentId,
                registry.capabilityId,
            )
        }
    }

    @Test
    fun `function marketplace dependency persists as a function-only declaration`() {
        val runtime = runtimeFixture.create()
        val contractId = UUID.randomUUID()
        insertCapability(id = contractId, key = "test-function-$contractId")
        fixtureCleaner.trackCapability(contractId)
        jdbcTemplate.update(
            "update agent_versions set status = 'DRAFT'::\"AgentVersionStatus\", capability_id = ? where id = ?",
            contractId,
            runtime.agentVersionId,
        )

        val response = dependencyService.create(
            sourceVersionId = runtime.agentVersionId,
            request = CreateDependencyRequest(
                functionContractId = contractId,
                providerScope = ProviderScope.MARKETPLACE,
                selectionStrategy = ProviderSelectionStrategy.LOWEST_PRICE,
                explorationPercent = 0,
                versionConstraint = "*",
                required = true,
                maxPriceAtomic = "1000",
                maxCalls = 1,
            ),
        )
        fixtureCleaner.trackDependency(response.id)

        assertEquals(contractId, response.functionContractId)
        assertEquals(ProviderScope.MARKETPLACE, response.providerScope)
        assertThrows(DomainClientException::class.java) {
            dependencyService.update(
                sourceVersionId = runtime.agentVersionId,
                dependencyId = response.id,
                request = UpdateDependencyRequest(providerScope = ProviderScope.PINNED),
            )
        }
    }

    @Test
    fun `manifest import rolls back agent and version when a dependency is invalid`() {
        val runtime = runtimeFixture.create()
        val contractId = UUID.randomUUID()
        val contractCode = "test-manifest-$contractId"
        val agentCode = "manifest-rollback-${UUID.randomUUID()}"
        jdbcTemplate.update(
            """
            insert into agent_capabilities
                (id, key, contract_version, name, description, response_format, input_schema, output_schema)
            values (?, ?, '1.0.0', 'Test', 'Test contract', 'JSON',
                '{"type":"object"}'::jsonb, '{"type":"object"}'::jsonb)
            """.trimIndent(),
            contractId,
            contractCode,
        )
        fixtureCleaner.trackCapability(contractId)

        assertThrows(DomainClientException::class.java) {
            manifestService.import(
                request = AgentManifestRequest(
                    content = invalidDependencyManifest(
                        agentCode = agentCode,
                        contractCode = contractCode,
                        developerId = runtime.developerId,
                    ),
                ),
            )
        }

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "select count(*) from agents where code = ?",
                Int::class.java,
                agentCode,
            ),
        )
    }

    private fun insertCapability(id: UUID, key: String) {
        jdbcTemplate.update(
            """
            insert into agent_capabilities
                (id, key, contract_version, name, description, response_format, input_schema, output_schema)
            values (?, ?, '1.0.0', 'Test', 'Test contract', 'JSON', '{"type":"object"}'::jsonb, '{"type":"object"}'::jsonb)
            """.trimIndent(),
            id,
            key,
        )
    }

    private fun tryAdvisoryLock(connection: Connection, key: String): Boolean {
        connection.prepareStatement("select pg_try_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result ->
                check(result.next())
                return result.getBoolean(1)
            }
        }
    }

    private fun invalidDependencyManifest(
        agentCode: String,
        contractCode: String,
        developerId: UUID,
    ): String {
        return """
            apiVersion: agentstore/v1
            agent:
              developerId: $developerId
              code: $agentCode
              name: Manifest rollback
              description: Transaction test
              version: 1.0.0
              usageType: internal_component
              function:
                code: $contractCode
                version: 1.0.0
              endpoint: http://127.0.0.1:8090/agents/test/invoke
              payment:
                priceAtomic: "1000"
                network: eip155:84532
                asset: USDC
                payTo: "0x0000000000000000000000000000000000000001"
            dependencies:
              - function:
                  code: $contractCode
                  version: 1.0.0
                providers:
                  scope: pinned
                  pinnedAgentCode: missing-agent
                constraints:
                  versionConstraint: "*"
                  required: true
                  maxPriceAtomic: "1000"
                  maxCalls: 1
                resolution:
                  explorationPercent: 0
        """.trimIndent()
    }
}
