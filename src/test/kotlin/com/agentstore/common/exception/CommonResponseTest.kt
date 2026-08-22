package com.agentstore.common.exception

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommonResponseTest {
    @Test
    fun `success response stores result and no error code`() {
        val response = CommonResponse.success(mapOf("id" to "execution-id"))

        assertThat(response.isSuccess).isTrue()
        assertThat(response.message).isEqualTo("요청이 성공했습니다.")
        assertThat(response.errorCode).isNull()
        assertThat(response.result).containsEntry("id", "execution-id")
    }

    @Test
    fun `failure response is an empty result with stable error code`() {
        val response = CommonResponse.failure(ErrorCode.DEPENDENCY_CYCLE_DETECTED, "cycle")

        assertThat(response.isSuccess).isFalse()
        assertThat(response.errorCode).isEqualTo("DEPENDENCY_409_003")
        assertThat(response.message).isEqualTo("cycle")
        assertThat(response.result).isNull()
        val json = ObjectMapper().writeValueAsString(response)
        assertThat(json).contains(
            "\"isSuccess\":false",
            "\"errorCode\":\"DEPENDENCY_409_003\"",
            "\"result\":null"
        )
    }
}
