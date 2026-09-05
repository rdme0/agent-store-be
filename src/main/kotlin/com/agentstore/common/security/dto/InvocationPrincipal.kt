package com.agentstore.common.security.dto

import java.time.Instant
import java.util.UUID

data class InvocationPrincipal(
    val executionId: UUID,
    val stepId: UUID,
    val agentVersionId: UUID,
    val callPath: List<String>,
    val expiresAt: Instant,
)
