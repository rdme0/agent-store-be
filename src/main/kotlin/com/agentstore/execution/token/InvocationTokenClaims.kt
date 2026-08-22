package com.agentstore.execution.token

import java.util.UUID

data class InvocationTokenClaims(
    val executionId: UUID,
    val stepId: UUID,
    val agentVersionId: UUID,
    val callPath: List<String>,
    val expiresAtEpochSeconds: Long,
)
