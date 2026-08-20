package com.agentstore.common.exception

import com.agentstore.common.exception.constants.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ErrorCodeTest {
    @Test
    fun `codes follow domain status and three digit number format`() {
        ErrorCode.values().forEach { code ->
            assertThat(code.code)
                .matches("^[A-Z]+_[1-5][0-9]{2}_\\d{3}$")
        }
    }

    @Test
    fun `numbers are unique and alphabetically stable within a domain and status`() {
        ErrorCode.values()
            .groupBy { it.domain to it.status }
            .values
            .forEach { codes ->
                assertThat(codes.map { it.number }).doesNotHaveDuplicates()
                assertThat(codes.sortedBy { it.name }.map { it.number })
                    .isSorted
            }
    }

    @Test
    fun `messages are Korean and dynamic arguments are formatted`() {
        assertThat(ErrorCode.DEPENDENCY_CYCLE_DETECTED.formatMessage("agent-a -> agent-b -> agent-a"))
            .isEqualTo("Dependency cycle이 감지되었습니다. 경로: agent-a -> agent-b -> agent-a")
        assertThat(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED.message)
            .contains("결제")
    }
}
