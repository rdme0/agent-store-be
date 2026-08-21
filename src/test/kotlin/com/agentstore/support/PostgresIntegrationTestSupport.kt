package com.agentstore.support

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.config.DatabaseUrlParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("postgres-integration")
abstract class PostgresIntegrationTestSupport {
    @Autowired
    private lateinit var properties: AgentStoreProperties

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    protected lateinit var fixtureCleaner: PostgresFixtureCleaner
    protected lateinit var runtimeFixture: PostgresRuntimeFixture

    @BeforeEach
    fun verifyIsolatedDatabaseAndCreateCleaner() {
        check(environment.matchesProfiles("postgres-integration"))
        val expectedDatabase = DatabaseUrlParser.parse(properties.databaseUrl).jdbcUrl
            .substringAfterLast('/')
            .substringBefore('?')
        val actualDatabase = jdbcTemplate.queryForObject("select current_database()", String::class.java)
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
