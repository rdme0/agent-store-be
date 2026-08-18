package com.agentstore.dependency.service

data class ResolvedGraph(val root: ResolvedNode, val warnings: List<com.agentstore.dependency.dto.QuoteWarning>)
