package com.agentstore.common.exception.handler

import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.common.exception.BusinessException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.payment.exception.PaymentExecutionException
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.JsonMappingException
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    private companion object {
        private const val TRACE_HEADER = "X-Trace-Id"
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(exception: BusinessException): ResponseEntity<Any> {
        log.warn(
            "Business exception: code={}, message={}",
            exception.errorCode.code,
            exception.message,
        )
        return failureEntity(
            errorCode = exception.errorCode,
            message = exception.message ?: exception.errorCode.message,
        )
    }

    @ExceptionHandler(PaymentExecutionException::class)
    fun handlePaymentExecution(exception: PaymentExecutionException): ResponseEntity<Any> {
        val errorCode = if (exception.failureCode == "FAILED_AFTER_PAYMENT") {
            ErrorCode.FAILED_AFTER_PAYMENT
        } else {
            ErrorCode.PAYMENT_FAILED
        }

        log.warn("Payment execution exception: code={}", exception.failureCode, exception)
        return failureEntity(errorCode = errorCode, message = errorCode.message)
    }

    @ExceptionHandler(PaymentOutcomeUnknownException::class)
    fun handlePaymentOutcomeUnknown(exception: PaymentOutcomeUnknownException): ResponseEntity<Any> {
        log.warn("Payment outcome requires reconciliation: code={}", exception.failureCode, exception)
        val errorCode = ErrorCode.PAYMENT_RECONCILIATION_REQUIRED
        return failureEntity(errorCode = errorCode, message = errorCode.message)
    }

    override fun handleMissingServletRequestParameter(
        ex: MissingServletRequestParameterException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        return failureEntity(
            errorCode = ErrorCode.INVALID_INPUT_VALUE,
            message = "필수 파라미터가 누락되었습니다: ${ex.parameterName}",
        )
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val message = ex.bindingResult.allErrors.firstOrNull()?.defaultMessage
            ?: ErrorCode.INVALID_INPUT_VALUE.message
        return failureEntity(errorCode = ErrorCode.INVALID_INPUT_VALUE, message = message)
    }

    @ExceptionHandler(BindException::class)
    fun handleBindException(exception: BindException): ResponseEntity<Any> {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: ErrorCode.INVALID_INPUT_VALUE.message
        return failureEntity(errorCode = ErrorCode.INVALID_INPUT_VALUE, message = message)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val cause = ex.cause
        val message = if (cause is JsonProcessingException) {
            resolveJsonErrorMessage(exception = cause)
        } else {
            "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요."
        }
        return failureEntity(errorCode = ErrorCode.INVALID_INPUT_VALUE, message = message)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(exception: MethodArgumentTypeMismatchException): ResponseEntity<Any> {
        return failureEntity(
            errorCode = ErrorCode.INVALID_INPUT_VALUE,
            message = "파라미터 '${exception.name}'의 값이 올바르지 않습니다.",
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(exception: IllegalArgumentException): ResponseEntity<Any> {
        return failureEntity(
            errorCode = ErrorCode.INVALID_INPUT_VALUE,
            message = exception.message ?: ErrorCode.INVALID_INPUT_VALUE.message,
        )
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(exception: DataIntegrityViolationException): ResponseEntity<Any> {
        log.warn("Data integrity violation", exception)
        val errorCode = ErrorCode.DATA_INTEGRITY_CONFLICT
        return failureEntity(errorCode = errorCode, message = errorCode.message)
    }

    @ExceptionHandler(JsonProcessingException::class)
    fun handleJsonProcessingException(exception: JsonProcessingException): ResponseEntity<Any> {
        return failureEntity(
            errorCode = ErrorCode.INVALID_INPUT_VALUE,
            message = resolveJsonErrorMessage(exception = exception),
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleAllUncaughtException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val businessException = findBusinessException(throwable = exception)
        if (businessException != null) {
            log.warn(
                "Nested business exception: code={}, path={}",
                businessException.errorCode.code,
                request.requestURI,
                exception,
            )
            return failureEntity(
                errorCode = businessException.errorCode,
                message = businessException.message ?: businessException.errorCode.message,
            )
        }

        log.error("Unhandled request error path={}", request.requestURI, exception)
        val errorCode = ErrorCode.INTERNAL_SERVER_ERROR
        return failureEntity(errorCode = errorCode, message = errorCode.message)
    }

    private fun findBusinessException(throwable: Throwable): BusinessException? {
        var current = throwable.cause
        while (current != null && current !== throwable) {
            if (current is BusinessException) {
                return current
            }
            current = current.cause
        }
        return null
    }

    private fun failureEntity(errorCode: ErrorCode, message: String): ResponseEntity<Any> {
        val traceId = MDC.get("traceId").takeUnless { value -> value.isNullOrBlank() }
            ?: UUID.randomUUID().toString()
        val headers = HttpHeaders().apply {
            add(TRACE_HEADER, traceId)
        }
        return ResponseEntity
            .status(errorCode.status)
            .headers(headers)
            .body(CommonResponse.failure(errorCode = errorCode, message = message))
    }

    private fun resolveJsonErrorMessage(exception: JsonProcessingException): String {
        return when (exception) {
            is InvalidFormatException -> "필드 '${fieldName(exception = exception)}'의 값이 올바르지 않습니다."
            is MismatchedInputException -> "필드 '${fieldName(exception = exception)}'의 타입이 올바르지 않습니다."
            is JsonMappingException -> "필드 '${fieldName(exception = exception)}'에 문제가 있습니다."
            else -> ErrorCode.INVALID_INPUT_VALUE.message
        }
    }

    private fun fieldName(exception: JsonMappingException): String {
        return exception.path.firstOrNull()?.fieldName ?: "unknown"
    }
}
