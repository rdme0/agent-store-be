package com.agentstore.external.dto.internal

import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import java.util.UUID

data class ExternalInvocationResultDto(
    val invocationId: UUID,
    val receiptToken: String,
    val response: ExternalInvocationExecutionResponse,
)
