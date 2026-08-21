package com.agentstore.execution.validation

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.fasterxml.jackson.databind.JsonNode

class AgentOutputFormatException(format: AgentResponseFormat) : RuntimeException(
    "Agent output does not match declared response format: $format"
)

object AgentOutputFormatValidator {
    fun validate(format: AgentResponseFormat, output: JsonNode) {
        when (format) {
            AgentResponseFormat.JSON -> return
            AgentResponseFormat.TEXT,
            AgentResponseFormat.MARKDOWN -> if (!output.isTextual) {
                throw AgentOutputFormatException(format)
            }
            AgentResponseFormat.STRUCTURED -> validateStructured(output)
        }
    }

    private fun validateStructured(output: JsonNode) {
        if (!output.isObject || !output.path("title").isTextual || output.path("title").asText().isBlank()) {
            throw AgentOutputFormatException(AgentResponseFormat.STRUCTURED)
        }
        val summary = output.get("summary")
        if (summary != null && !summary.isTextual) {
            throw AgentOutputFormatException(AgentResponseFormat.STRUCTURED)
        }
        val sections = output.path("sections")
        if (!sections.isArray || sections.size() == 0) {
            throw AgentOutputFormatException(AgentResponseFormat.STRUCTURED)
        }
        sections.forEach { section ->
            val label = section.path("label")
            val value = section.get("value")
            if (!section.isObject || !label.isTextual || label.asText().isBlank() || value == null || !isScalar(value)) {
                throw AgentOutputFormatException(AgentResponseFormat.STRUCTURED)
            }
        }
    }

    private fun isScalar(value: JsonNode): Boolean {
        return value.isTextual || value.isNumber || value.isBoolean
    }
}
