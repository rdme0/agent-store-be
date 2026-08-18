package com.agentstore.dependency.model.vo

import com.agentstore.dependency.model.entity.AgentDependency

data class ResolvedEdge(val dependency: AgentDependency, val resolved: ResolvedNode?)
