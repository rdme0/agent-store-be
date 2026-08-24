package com.agentstore.execution.dto.internal

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.util.UUID

data class ExecutionStartDto(
    val quoteId: UUID,
    val maxBudgetAtomic: BigInteger,
    val question: String?,
    val input: JsonNode?,
    val allowExpiredQuote: Boolean,
)
