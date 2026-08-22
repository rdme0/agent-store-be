package com.agentstore.dependency.model.vo

import com.agentstore.dependency.dto.response.QuoteWarning

data class ResolvedGraph(
    val root: ResolvedNode,
    val warnings: List<QuoteWarning>,
)
