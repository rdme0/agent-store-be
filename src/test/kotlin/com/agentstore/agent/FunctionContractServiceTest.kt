package com.agentstore.agent

import com.agentstore.agent.dto.request.CreateFunctionContractRequest
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.repository.FunctionContractRepository
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.agent.service.FunctionContractService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.execution.service.ProviderMetricService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class FunctionContractServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val service = FunctionContractService(
        functionContractRepository = mock(FunctionContractRepository::class.java),
        versionRepository = mock(AgentVersionRepository::class.java),
        agentRepository = mock(AgentRepository::class.java),
        objectMapper = objectMapper,
        providerMetricService = mock(ProviderMetricService::class.java),
    )

    @Test
    fun `remote static and dynamic references and non-object input schemas are rejected`() {
        listOf(
            schema("""{"type":"object","properties":{"news":{"${'$'}ref":"https://example.com/news.json"}}}"""),
            schema("""{"type":"object","properties":{"news":{"${'$'}dynamicRef":"https://example.com/news.json"}}}"""),
            schema("""{"type":"string"}"""),
        ).forEach { inputSchema ->
            val exception = assertThrows(DomainClientException::class.java) {
                service.create(request(inputSchema = inputSchema))
            }
            assertEquals(ErrorCode.INVALID_FUNCTION_CONTRACT_SCHEMA, exception.errorCode)
        }
    }

    @Test
    fun `runtime instance mismatch uses the requested execution error code`() {
        val exception = assertThrows(DomainClientException::class.java) {
            service.validateInstance(
                schema = schema("""{"type":"object","required":["question"]}"""),
                value = schema("""{"input":{}}"""),
                errorCode = ErrorCode.AGENT_INPUT_SCHEMA_INVALID,
            )
        }
        assertEquals(ErrorCode.AGENT_INPUT_SCHEMA_INVALID, exception.errorCode)
    }

    @Test
    fun `oversized and deeply nested schemas are rejected`() {
        val oversized = schema("""{"type":"object","description":"${"x".repeat(65_536)}"}""")
        var deeplyNested = schema("""{"type":"object"}""")
        repeat(33) {
            deeplyNested = objectMapper.createObjectNode().set<JsonNode>("properties", deeplyNested)
        }

        listOf(oversized, deeplyNested).forEach { inputSchema ->
            val exception = assertThrows(DomainClientException::class.java) {
                service.create(request(inputSchema = inputSchema))
            }
            assertEquals(ErrorCode.INVALID_FUNCTION_CONTRACT_SCHEMA, exception.errorCode)
        }
    }

    @Test
    fun `invalid contract semver is rejected`() {
        val invalid = request(inputSchema = schema("""{"type":"object"}""")).copy(contractVersion = "v1")

        val exception = assertThrows(DomainClientException::class.java) {
            service.create(invalid)
        }

        assertEquals(ErrorCode.INVALID_SEMVER, exception.errorCode)
    }

    @Test
    fun `function code permits lowercase words joined by hyphens`() {
        val validator = Validation.buildDefaultValidatorFactory().validator

        val violations = validator.validate(
            request(inputSchema = schema("""{"type":"object"}""")).copy(
                code = "stock-news-analysis",
            ),
        )

        assertTrue(violations.none { violation -> violation.propertyPath.toString() == "code" })
    }

    @Test
    fun `structured function contract schema must match the runtime title and sections contract`() {
        val invalidSchemas = listOf(
            schema(
                """
                {
                  "type":"object",
                  "required":["title","sections"],
                  "properties":{"title":{"type":"number"},"sections":{"type":"string"}}
                }
                """.trimIndent(),
            ),
            schema(
                """
                {
                  "type":"object",
                  "required":["title","sections"],
                  "properties":{
                    "title":{"type":"string"},
                    "sections":{"type":"array","items":{"type":"object"}}
                  }
                }
                """.trimIndent(),
            ),
        )

        invalidSchemas.forEach { outputSchema ->
            val exception = assertThrows(DomainClientException::class.java) {
                service.create(
                    request(inputSchema = schema("""{"type":"object"}""")).copy(
                        responseFormat = AgentResponseFormat.STRUCTURED,
                        outputSchema = outputSchema,
                    ),
                )
            }
            assertEquals(ErrorCode.INVALID_FUNCTION_CONTRACT_SCHEMA, exception.errorCode)
        }
    }

    private fun request(inputSchema: JsonNode): CreateFunctionContractRequest {
        return CreateFunctionContractRequest(
            code = "news-analysis",
            contractVersion = "1.0.0",
            name = "News",
            description = "News contract",
            responseFormat = AgentResponseFormat.JSON,
            inputSchema = inputSchema,
            outputSchema = schema("""{"type":"object"}"""),
        )
    }

    private fun schema(value: String): JsonNode {
        return objectMapper.readTree(value)
    }
}
