package com.agentstore.dependency.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateDependencyRequest(val targetAgentId: UUID, @field:NotBlank @field:Size(max = 128) val versionConstraint: String, val required: Boolean = true, @field:Pattern(regexp = "^[0-9]+$") val maxPriceAtomic: String, @field:Min(1) @field:Max(5) val maxCalls: Int = 1)
data class UpdateDependencyRequest(@field:Size(max = 128) val versionConstraint: String? = null, val required: Boolean? = null, @field:Pattern(regexp = "^[0-9]+$") val maxPriceAtomic: String? = null, @field:Min(1) @field:Max(5) val maxCalls: Int? = null) {
    fun isEmpty() = versionConstraint == null && required == null && maxPriceAtomic == null && maxCalls == null
}
data class QuoteRequest(@field:Size(max = 128) val versionConstraint: String? = null)
