package com.agentstore.common.exception

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.common.exception.handler.GlobalExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `business exception becomes common response and trace header`() {
        val response = handler.handleBusinessException(
            DomainClientException(ErrorCode.DEPENDENCY_CYCLE_DETECTED, "a -> b -> a")
        )

        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.headers.getFirst("X-Trace-Id")).isNotBlank()
        val body = response.body as com.agentstore.common.dto.response.CommonResponse<*>
        assertThat(body.isSuccess()).isFalse()
        assertThat(body.errorCode()).isEqualTo("DEPENDENCY_409_003")
        assertThat(body.result()).isNull()
    }

    @Test
    fun `unexpected exception never exposes cause details`() {
        val response = handler.handleAllUncaughtException(
            IllegalStateException("jdbc password=secret"),
            org.springframework.mock.web.MockHttpServletRequest()
        )

        assertThat(response.statusCode.value()).isEqualTo(500)
        val body = response.body as com.agentstore.common.dto.response.CommonResponse<*>
        assertThat(body.errorCode()).isEqualTo("COMMON_500_001")
        assertThat(body.message()).doesNotContain("password")
        assertThat(body.result()).isNull()
    }

    @Test
    fun `nested business exception keeps its domain error code`() {
        val nested = DomainClientException(ErrorCode.QUOTE_EXPIRED)
        val response = handler.handleAllUncaughtException(
            IllegalStateException("wrapper", nested),
            org.springframework.mock.web.MockHttpServletRequest()
        )

        val body = response.body as com.agentstore.common.dto.response.CommonResponse<*>
        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(body.errorCode()).isEqualTo("QUOTE_409_001")
    }
}
