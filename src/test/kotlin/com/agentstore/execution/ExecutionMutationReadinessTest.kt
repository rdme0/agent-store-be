package com.agentstore.execution

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.execution.guard.ExecutionMutationReadiness
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionMutationReadinessTest {
    @Test
    fun `execution mutations are rejected until recovery completes`() {
        val readiness = ExecutionMutationReadiness()
        assertThatThrownBy { readiness.requireReady() }
            .isInstanceOf(DomainClientException::class.java)
            .hasMessage("Execution 복구가 진행 중입니다.")
        readiness.markReady()
        readiness.requireReady()
    }
}
