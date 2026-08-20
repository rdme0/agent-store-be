package com.agentstore.common.migration

import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate

class FlywayCompatibilityConfigurationTest {
    @Test
    fun `existing prisma v8 schema without history is baselined before v9 migration`() {
        val jdbcTemplate = jdbcTemplate(history = false, hasAgentStoreTables = true)
        val validator = mock(SchemaCompatibilityValidator::class.java)
        val flyway = mock(org.flywaydb.core.Flyway::class.java)

        FlywayCompatibilityConfiguration().flywayMigrationStrategy(jdbcTemplate, validator).migrate(flyway)

        verify(validator).assertExistingAgentStoreSchema()
        verify(flyway).baseline()
        verify(flyway).migrate()
    }

    @Test
    fun `schema mismatch fails closed before flyway baseline or migration`() {
        val jdbcTemplate = jdbcTemplate(history = false, hasAgentStoreTables = true)
        val validator = mock(SchemaCompatibilityValidator::class.java)
        val flyway = mock(org.flywaydb.core.Flyway::class.java)
        doThrow(SchemaCompatibilityException("schema mismatch")).`when`(validator).assertExistingAgentStoreSchema()

        assertThatIllegalStateException().isThrownBy {
            FlywayCompatibilityConfiguration().flywayMigrationStrategy(jdbcTemplate, validator).migrate(flyway)
        }.withMessageContaining("schema mismatch")

        verify(validator).assertExistingAgentStoreSchema()
        verifyNoMoreInteractions(flyway)
    }

    private fun jdbcTemplate(history: Boolean, hasAgentStoreTables: Boolean): JdbcTemplate {
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        `when`(jdbcTemplate.queryForObject(anyString(), eq(Boolean::class.java))).thenReturn(
            history,
            hasAgentStoreTables
        )
        return jdbcTemplate
    }
}
