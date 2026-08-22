package com.agentstore.common.dto.response

import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CommonResponse<T>(
    @get:JsonProperty("isSuccess")
    val isSuccess: Boolean,
    val message: String,
    val errorCode: String?,
    val result: T?,
) {
    companion object {
        private const val SUCCESS_MESSAGE = "요청이 성공했습니다."

        fun emptySuccess(): CommonResponse<Void> {
            return CommonResponse(
                isSuccess = true,
                message = SUCCESS_MESSAGE,
                errorCode = null,
                result = null,
            )
        }

        fun <T> success(result: T): CommonResponse<T> {
            return CommonResponse(
                isSuccess = true,
                message = SUCCESS_MESSAGE,
                errorCode = null,
                result = result,
            )
        }

        fun failure(errorCode: ErrorCode): CommonResponse<Void> {
            return failure(errorCode = errorCode, message = errorCode.message)
        }

        fun failure(errorCode: ErrorCode, message: String): CommonResponse<Void> {
            return CommonResponse(
                isSuccess = false,
                message = message,
                errorCode = errorCode.code,
                result = null,
            )
        }
    }
}
