package com.agentstore.execution

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.execution.token.InvocationTokenService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.*

class InvocationTokenServiceTest {
    private val service = InvocationTokenService(
        AgentStoreProperties(
            corsOrigins = listOf("*"),
            runtimeTokenSecret = "test-secret",
            databaseUrl = "jdbc:postgresql://localhost:15432/agent_store",
        ),
        jacksonObjectMapper(),
    )

    @Test
    fun `issued token round trips claims`() {
        val executionId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val versionId = UUID.randomUUID()

        val claims = service.verify(service.issue(executionId, stepId, versionId, listOf("root", "child")))

        assertEquals(executionId, claims.executionId)
        assertEquals(stepId, claims.stepId)
        assertEquals(versionId, claims.agentVersionId)
        assertEquals(listOf("root", "child"), claims.callPath)
    }

    @Test
    fun `tampered token is rejected`() {
        val token = service.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), emptyList())
        assertThrows(RuntimeException::class.java) { service.verify("$token-tampered") }
    }
}
