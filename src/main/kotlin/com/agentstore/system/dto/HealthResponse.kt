package com.agentstore.system.dto

import java.time.Instant

data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
    val timestamp: Instant,
)
