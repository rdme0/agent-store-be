package com.agentstore.agent.dto.request

import com.agentstore.agent.model.vo.AgentListSort
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class AgentListRequest(
    @field:Parameter(description = "페이지당 항목 수", example = "20")
    @field:Min(1)
    @field:Max(50)
    val limit: Int = 20,
    @field:Parameter(description = "이전 응답의 서명된 nextCursor")
    val cursor: String? = null,
    @field:Parameter(description = "Agent 이름 또는 설명 검색어", example = "risk")
    @field:Size(max = 100)
    val q: String? = null,
    @field:Parameter(description = "정렬 기준", example = "newest")
    @field:Schema(
        allowableValues = ["newest", "name_asc"],
        defaultValue = "newest",
    )
    @field:Pattern(regexp = "newest|name_asc")
    val sort: String = AgentListSort.NEWEST.value,
) {
    fun sortType(): AgentListSort {
        return AgentListSort.from(value = sort)
    }
}
