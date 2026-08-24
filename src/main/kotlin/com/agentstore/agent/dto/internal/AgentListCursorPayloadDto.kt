package com.agentstore.agent.dto.internal

import com.agentstore.agent.model.vo.AgentListSort
import com.agentstore.agent.model.vo.AgentView
import java.time.Instant

data class AgentListCursorPayloadDto(
    val sort: AgentListSort,
    val query: String?,
    val view: AgentView,
    val id: String,
    val createdAt: Instant? = null,
    val nameKey: String? = null,
)
