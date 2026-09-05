package com.agentstore.developer.dto.response

import java.time.Instant
import java.util.UUID

data class DemoDeveloperResponse(
    val id: UUID,
    val displayName: String,
)

data class DemoAccessResponse(
    val accessToken: String,
    val expiresAt: Instant,
)
