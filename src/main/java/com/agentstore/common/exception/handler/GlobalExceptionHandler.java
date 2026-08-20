package com.agentstore.common.exception.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.agentstore.common.dto.response.CommonResponse;
import com.agentstore.common.exception.BusinessException;
import com.agentstore.common.exception.constants.ErrorCode;
import com.agentstore.payment.exception.PaymentExecutionException;
import com.agentstore.payment.exception.PaymentOutcomeUnknownException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TRACE_HEADER = "X-Trace-Id";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
        log.warn("Business exception: code={}, message={}", exception.getErrorCode().getCode(), exception.getMessage());
        return failureEntity(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(PaymentExecutionException.class)
    public ResponseEntity<Object> handlePaymentExecution(PaymentExecutionException exception) {
        ErrorCode errorCode = "FAILED_AFTER_PAYMENT".equals(exception.getFailureCode())
                ? ErrorCode.FAILED_AFTER_PAYMENT
                : ErrorCode.PAYMENT_FAILED;
        log.warn("Payment execution exception: code={}", exception.getFailureCode(), exception);
        return failureEntity(errorCode, errorCode.getMessage());
    }

    @ExceptionHandler(PaymentOutcomeUnknownException.class)
    public ResponseEntity<Object> handlePaymentOutcomeUnknown(PaymentOutcomeUnknownException exception) {
        log.warn("Payment outcome requires reconciliation: code={}", exception.getFailureCode(), exception);
        return failureEntity(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED, ErrorCode.PAYMENT_RECONCILIATION_REQUIRED.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        return failureEntity(ErrorCode.INVALID_INPUT_VALUE, "필수 파라미터가 누락되었습니다: " + exception.getParameterName());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(ObjectError::getDefaultMessage)
                .orElse(ErrorCode.INVALID_INPUT_VALUE.getMessage());
        return failureEntity(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    @ExceptionHandler(BindException.class)
    protected ResponseEntity<Object> handleBindException(
            BindException exception,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.INVALID_INPUT_VALUE.getMessage());
        return failureEntity(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        String message = "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요.";
        Throwable cause = exception.getCause();
        if (cause instanceof JsonProcessingException jsonException) {
            message = resolveJsonErrorMessage(jsonException);
        }
        return failureEntity(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String message = String.format("파라미터 '%s'의 값이 올바르지 않습니다.", exception.getName());
        return failureEntity(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException exception) {
        return failureEntity(ErrorCode.INVALID_INPUT_VALUE, exception.getMessage() == null
                ? ErrorCode.INVALID_INPUT_VALUE.getMessage()
                : exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        log.warn("Data integrity violation", exception);
        return failureEntity(ErrorCode.DATA_INTEGRITY_CONFLICT, ErrorCode.DATA_INTEGRITY_CONFLICT.getMessage());
    }

    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<Object> handleJsonProcessingException(JsonProcessingException exception) {
        return failureEntity(ErrorCode.INVALID_INPUT_VALUE, resolveJsonErrorMessage(exception));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllUncaughtException(Exception exception, HttpServletRequest request) {
        BusinessException nestedBusinessException = findBusinessException(exception);
        if (nestedBusinessException != null) {
            log.warn("Nested business exception: code={}, path={}",
                    nestedBusinessException.getErrorCode().getCode(), request.getRequestURI(), exception);
            return failureEntity(nestedBusinessException.getErrorCode(), nestedBusinessException.getMessage());
        }
        log.error("Unhandled request error path={}", request.getRequestURI(), exception);
        return failureEntity(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private BusinessException findBusinessException(Throwable throwable) {
        Throwable current = throwable.getCause();
        while (current != null && current != throwable) {
            if (current instanceof BusinessException businessException) {
                return businessException;
            }
            current = current.getCause();
        }
        return null;
    }

    private ResponseEntity<Object> failureEntity(ErrorCode errorCode, String message) {
        String traceId = org.slf4j.MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(TRACE_HEADER, traceId);
        return ResponseEntity.status(errorCode.getStatus())
                .headers(headers)
                .body(CommonResponse.failure(errorCode, message));
    }

    private String resolveJsonErrorMessage(JsonProcessingException exception) {
        if (exception instanceof InvalidFormatException invalidFormatException) {
            return "필드 '%s'의 값이 올바르지 않습니다.".formatted(fieldName(invalidFormatException));
        }
        if (exception instanceof MismatchedInputException mismatchedInputException) {
            return "필드 '%s'의 타입이 올바르지 않습니다.".formatted(fieldName(mismatchedInputException));
        }
        if (exception instanceof JsonMappingException mappingException) {
            return "필드 '%s'에 문제가 있습니다.".formatted(fieldName(mappingException));
        }
        return ErrorCode.INVALID_INPUT_VALUE.getMessage();
    }

    private String fieldName(JsonMappingException exception) {
        if (exception.getPath().isEmpty()) {
            return "unknown";
        }
        return exception.getPath().getFirst().getFieldName();
    }
}
