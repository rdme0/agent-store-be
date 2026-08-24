package com.agentstore.external.dto.internal

import com.agentstore.external.dto.response.ExternalInvocationExecutionResponse
import com.agentstore.external.dto.response.ExternalInvocationIntentResponse

data class ExternalInvocationIntentCreatedDto(
    val response: ExternalInvocationIntentResponse,
    val receiptToken: String,
)

data class ExternalInvocationExecuteResultDto(
    val paymentRequiredHeader: String?,
    val paymentResponseHeader: String?,
    val response: ExternalInvocationExecutionResponse?,
)
