package com.agentstore.execution.codec

import com.fasterxml.jackson.databind.JsonNode

/** Decodes the stable transport envelope emitted by the bundled demo-agent only. */
object RuntimeOutputEnvelope {
    private const val DEMO_AGENT_TRANSPORT = "agentstore-demo/v1"

    fun extract(rawOutput: JsonNode): JsonNode {
        if (!isDemoAgentEnvelope(rawOutput)) {
            return rawOutput
        }
        return rawOutput.path("output")
    }

    private fun isDemoAgentEnvelope(rawOutput: JsonNode): Boolean {
        return rawOutput.isObject &&
            rawOutput.path("transport").asText() == DEMO_AGENT_TRANSPORT &&
            rawOutput.path("agent").isTextual &&
            rawOutput.path("agent").asText().isNotBlank() &&
            rawOutput.path("dependencyResults").isObject &&
            rawOutput.has("output")
    }
}
