package com.agentstore.common.migration

import com.agentstore.support.PostgresIntegrationTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Test

@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
class PostgresSchemaIntegrationTest : PostgresIntegrationTestSupport() {
    @Test
    fun `flyway applies every current migration`() {
        val version = jdbcTemplate.queryForObject(
            "select version from flyway_schema_history where success = true order by installed_rank desc limit 1",
            String::class.java,
        )
        assertEquals("13", version)
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
}
