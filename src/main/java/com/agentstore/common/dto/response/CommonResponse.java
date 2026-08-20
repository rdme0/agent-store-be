package com.agentstore.common.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.agentstore.common.exception.constants.ErrorCode;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CommonResponse<T>(
        boolean isSuccess,
        String message,
        String errorCode,
        T result
) {

    private static final String SUCCESS_MESSAGE = "요청이 성공했습니다.";

    public static CommonResponse<Void> emptySuccess() {
        return new CommonResponse<>(true, SUCCESS_MESSAGE, null, null);
    }

    public static <T> CommonResponse<T> success(T result) {
        return new CommonResponse<>(true, SUCCESS_MESSAGE, null, result);
    }

    public static CommonResponse<Void> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.getMessage());
    }

    public static CommonResponse<Void> failure(ErrorCode errorCode, String message) {
        return new CommonResponse<>(false, message, errorCode.getCode(), null);
    }
}
