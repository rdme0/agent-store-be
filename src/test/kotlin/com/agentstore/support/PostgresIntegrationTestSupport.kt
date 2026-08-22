package com.agentstore.support

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    properties = [
        "spring.datasource.url=\${INTEGRATION_DATASOURCE_URL}",
        "spring.datasource.username=\${INTEGRATION_DATASOURCE_USERNAME}",
        "spring.datasource.password=\${INTEGRATION_DATASOURCE_PASSWORD}",
        "agent-store.service-name=agent-store-api",
        "agent-store.api-version=0.1.0",
        "agent-store.runtime-callback-base-url=http://127.0.0.1:8080",
        "agent-store.cors-origins=http://localhost:*",
        "agent-store.runtime-token-secret=integration-runtime-secret",
        "agent-store.payment-mode=simulated",
    ],
)
@ActiveProfiles("postgres-integration")
abstract class PostgresIntegrationTestSupport {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    protected lateinit var fixtureCleaner: PostgresFixtureCleaner
    protected lateinit var runtimeFixture: PostgresRuntimeFixture

    @BeforeEach
    fun verifyIsolatedDatabaseAndCreateCleaner() {
        check(environment.matchesProfiles("postgres-integration"))
        val expectedDatabase = checkNotNull(System.getenv("INTEGRATION_DATASOURCE_URL"))
            .substringAfterLast('/')
            .substringBefore('?')
        val actualDatabase =
            jdbcTemplate.queryForObject("select current_database()", String::class.java)
        check(actualDatabase == expectedDatabase) { "Integration test connected to '$actualDatabase', expected '$expectedDatabase'" }
        check(actualDatabase != "agent_store") {
            "PostgreSQL integration tests must use the dedicated agent_store_integration database"
        }
        fixtureCleaner = PostgresFixtureCleaner(jdbcTemplate)
        runtimeFixture = PostgresRuntimeFixture(jdbcTemplate, fixtureCleaner)
    }

    @AfterEach
    fun removeOnlyTrackedFixtures() {
        if (::fixtureCleaner.isInitialized) {
            fixtureCleaner.cleanup()
        }
    }

}
