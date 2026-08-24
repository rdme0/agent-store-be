package com.agentstore.agent.dto.internal

import java.util.UUID

data class FunctionProviderDto(
    val agentId: UUID,
    val agentCode: String,
    val agentName: String,
    val versionId: UUID,
    val semver: String,
    val priceAtomic: String,
)
