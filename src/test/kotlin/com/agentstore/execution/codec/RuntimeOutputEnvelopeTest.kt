package com.agentstore.execution.codec

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuntimeOutputEnvelopeTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `demo agent transport envelope unwraps its output`() {
        val rawOutput = objectMapper.readTree(
            """
            {
              "transport": "agentstore-demo/v1",
              "agent": "financial-analysis",
              "output": {"summary": "재무 건전성은 보통 수준입니다."},
              "dependencyResults": {}
            }
            """.trimIndent(),
        )

        val output = RuntimeOutputEnvelope.extract(rawOutput)

        assertEquals("재무 건전성은 보통 수준입니다.", output.path("summary").asText())
    }

    @Test
    fun `direct JSON output keeps a business output field`() {
        val rawOutput = objectMapper.readTree(
            """
            {"output": {"status": "approved"}, "decision": "complete"}
            """.trimIndent(),
        )

        val output = RuntimeOutputEnvelope.extract(rawOutput)

        assertEquals(rawOutput, output)
    }

    @Test
    fun `ordinary Agent output with an envelope shaped business object stays intact`() {
        val rawOutput = objectMapper.readTree(
            """
            {
              "agent": "business-agent",
              "output": {"status": "approved"},
              "dependencyResults": {},
              "transport": "business-workflow/v1"
            }
            """.trimIndent(),
        )

        val output = RuntimeOutputEnvelope.extract(rawOutput)

        assertEquals(rawOutput, output)
    }
}
