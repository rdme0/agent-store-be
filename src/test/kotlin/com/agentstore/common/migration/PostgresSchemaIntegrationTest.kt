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
        assertEquals("20", version)
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
                "select count(*) from agents where slug = ?",
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
