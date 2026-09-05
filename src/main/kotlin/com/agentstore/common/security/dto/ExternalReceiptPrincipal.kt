package com.agentstore.common.security.dto

import java.time.Instant
import java.util.UUID

data class ExternalReceiptPrincipal(
    val invocationId: UUID,
    val expiresAt: Instant,
)
