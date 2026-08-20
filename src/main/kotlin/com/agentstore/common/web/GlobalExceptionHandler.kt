package com.agentstore.common.web

import com.agentstore.common.dto.response.ApiError
import com.agentstore.common.dto.response.ErrorBody
import com.agentstore.common.exception.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.*

@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApi(exception: ApiException, request: HttpServletRequest): ResponseEntity<ApiError> {
        return response(exception.status, exception.code, exception.message, exception.details)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest
    ): ResponseEntity<ApiError> {
        return response(
            422,
            "VALIDATION_ERROR",
            "Request validation failed",
            exception.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") })
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        logger.error("Unhandled request error path={}", request.requestURI, exception)
        return response(500, "INTERNAL_SERVER_ERROR", "Internal server error", null)
    }

    private fun response(status: Int, code: String, message: String, details: Any?): ResponseEntity<ApiError> {
        val traceId = org.slf4j.MDC.get("traceId") ?: UUID.randomUUID().toString()
        return ResponseEntity.status(status).body(ApiError(ErrorBody(code, message, details), traceId))
    }
}
