package com.agentstore.common.web

data class ApiError(
    val code: String,
    val message: String,
    val details: Any? = null,
    val traceId: String,
)

class ApiException(
    val code: String,
    override val message: String,
    val status: Int = 400,
    val details: Any? = null,
) : RuntimeException(message)
