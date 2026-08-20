package com.agentstore.execution

import com.agentstore.common.exception.ApiException
import com.agentstore.execution.guard.ExecutionMutationReadiness
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionMutationReadinessTest {
    @Test
    fun `execution mutations are rejected until recovery completes`() {
        val readiness = ExecutionMutationReadiness()
        assertThatThrownBy { readiness.requireReady() }
            .isInstanceOf(ApiException::class.java)
            .hasMessage("Execution mutations are unavailable while payment recovery is running")
        readiness.markReady()
        readiness.requireReady()
    }
}
