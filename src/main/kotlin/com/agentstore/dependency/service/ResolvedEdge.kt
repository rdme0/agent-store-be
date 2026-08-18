package com.agentstore.dependency.service

import com.agentstore.dependency.model.entity.AgentDependency

data class ResolvedEdge(val dependency: AgentDependency, val resolved: ResolvedNode?)
