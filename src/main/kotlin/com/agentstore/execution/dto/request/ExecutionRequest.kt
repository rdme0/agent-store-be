package com.agentstore.execution.dto.request

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateExecutionRequest(
    val quoteId: UUID,
    @field:NotBlank @field:Pattern(regexp = "^[0-9]+$") val maxBudgetAtomic: String,
    @field:Size(min = 1, max = 4000) val question: String? = null,
    val input: JsonNode? = null,
)
