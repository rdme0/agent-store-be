package com.agentstore.common.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class DatabaseUrlParserTest {
    @Test
    fun `converts prisma postgres url to jdbc properties`() {
        val result = DatabaseUrlParser.parse("postgresql://postgres:p%40ss@localhost:5432/agent_store?schema=public")

        assertThat(result.jdbcUrl).isEqualTo("jdbc:postgresql://localhost:5432/agent_store?currentSchema=public")
        assertThat(result.username).isEqualTo("postgres")
        assertThat(result.password).isEqualTo("p@ss")
    }

    @Test
    fun `keeps jdbc url and extracts credentials`() {
        val result = DatabaseUrlParser.parse("jdbc:postgresql://localhost:5432/agent_store?currentSchema=public")

        assertThat(result.jdbcUrl).isEqualTo("jdbc:postgresql://localhost:5432/agent_store?currentSchema=public")
        assertThat(result.username).isEmpty()
        assertThat(result.password).isEmpty()
    }

    @Test
    fun `rejects unsupported database url`() {
        assertThatIllegalArgumentException().isThrownBy { DatabaseUrlParser.parse("mysql://localhost/agent_store") }
    }
}
