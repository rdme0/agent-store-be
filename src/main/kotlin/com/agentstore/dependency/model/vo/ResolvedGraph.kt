package com.agentstore.dependency.model.vo

data class ResolvedGraph(val root: ResolvedNode, val warnings: List<com.agentstore.dependency.dto.response.QuoteWarning>)
