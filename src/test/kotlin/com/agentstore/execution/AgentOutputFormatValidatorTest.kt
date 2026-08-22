package com.agentstore.execution

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.execution.validation.AgentOutputFormatException
import com.agentstore.execution.validation.AgentOutputFormatValidator
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentOutputFormatValidatorTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `text and markdown accept only strings`() {
        assertDoesNotThrow {
            AgentOutputFormatValidator.validate(
                AgentResponseFormat.TEXT,
                objectMapper.readTree("\"hello\"")
            )
            AgentOutputFormatValidator.validate(
                AgentResponseFormat.MARKDOWN,
                objectMapper.readTree("\"# hello\"")
            )
        }
        assertThrows(AgentOutputFormatException::class.java) {
            AgentOutputFormatValidator.validate(
                AgentResponseFormat.TEXT,
                objectMapper.readTree("{\"value\":1}")
            )
        }
    }

    @Test
    fun `structured requires title and non-empty scalar sections`() {
        val valid = objectMapper.readTree(
            """{"title":"Summary","sections":[{"label":"Score","value":0.82},{"label":"Ready","value":true}]}"""
        )
        assertDoesNotThrow {
            AgentOutputFormatValidator.validate(
                AgentResponseFormat.STRUCTURED,
                valid
            )
        }

        val invalid = objectMapper.readTree(
            """{"title":"Summary","sections":[{"label":"Nested","value":{"bad":true}}]}"""
        )
        assertThrows(AgentOutputFormatException::class.java) {
            AgentOutputFormatValidator.validate(AgentResponseFormat.STRUCTURED, invalid)
        }
    }

    @Test
    fun `json accepts arbitrary json`() {
        assertDoesNotThrow {
            AgentOutputFormatValidator.validate(
                AgentResponseFormat.JSON,
                objectMapper.readTree("{\"anything\":[1,true,null]}")
            )
        }
    }
}
