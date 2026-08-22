package com.agentstore.x402.dto.internal

import org.springframework.http.HttpHeaders

data class X402AgentResponseDto(
    val status: Int,
    val headers: HttpHeaders,
    val body: ByteArray,
)
