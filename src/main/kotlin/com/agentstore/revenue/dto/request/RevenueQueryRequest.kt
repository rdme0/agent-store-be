package com.agentstore.revenue.dto.request

import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID

data class RevenueQueryRequest(
    @field:Parameter(description = "이전 응답의 nextCursor")
    val cursor: UUID? = null,
    @field:Parameter(description = "페이지당 항목 수", example = "20")
    @field:Min(1)
    @field:Max(100)
    val limit: Int = 20,
)
