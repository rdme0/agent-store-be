package com.agentstore.common.exception

class ApiException(
    val code: String,
    override val message: String,
    val status: Int = 400,
    val details: Any? = null,
) : RuntimeException(message)
