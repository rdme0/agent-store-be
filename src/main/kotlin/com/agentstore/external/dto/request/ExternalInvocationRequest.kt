package com.agentstore.external.dto.request

import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateExternalInvocationIntentRequest(
    @field:Size(max = 120) val agentCode: String? = null,
    @field:Size(max = 120) val functionCode: String? = null,
    @field:Size(max = 32) val contractVersion: String? = null,
    val selectionStrategy: ProviderSelectionStrategy? = null,
    @field:Size(max = 128) val versionConstraint: String? = null,
    @field:NotBlank @field:Pattern(regexp = "^[1-9][0-9]*$") val maxTotalAtomic: String,
    @field:Size(min = 1, max = 4000) val question: String? = null,
    val input: Any? = null,
)
