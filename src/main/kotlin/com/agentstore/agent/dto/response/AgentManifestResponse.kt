package com.agentstore.agent.dto.response

import java.util.UUID

data class AgentManifestValidationResponse(
    val canonicalContent: String,
    val sha256: String,
    val agentCode: String,
    val functionCode: String,
)

data class AgentManifestImportResponse(
    val agentId: UUID,
    val versionId: UUID,
    val agentCode: String,
    val sha256: String,
)

data class AgentManifestResponse(
    val versionId: UUID,
    val content: String,
    val sha256: String,
)
