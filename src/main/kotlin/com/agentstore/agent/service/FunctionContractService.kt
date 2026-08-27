package com.agentstore.agent.service

import com.agentstore.agent.dto.internal.FunctionProviderDto
import com.agentstore.agent.dto.request.CreateFunctionContractRequest
import com.agentstore.agent.dto.response.FunctionContractResponse
import com.agentstore.agent.dto.response.FunctionProviderMetricResponse
import com.agentstore.agent.model.entity.FunctionContract
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.FunctionContractRepository
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.agentstore.execution.service.ProviderMetricService
import java.util.UUID
import org.semver4j.Semver
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class FunctionContractService(
    private val functionContractRepository: FunctionContractRepository,
    private val versionRepository: AgentVersionRepository,
    private val agentRepository: AgentRepository,
    private val objectMapper: ObjectMapper,
    private val providerMetricService: ProviderMetricService,
) {
    companion object {
        private const val MAX_SCHEMA_BYTES = 65_536
        private const val MAX_SCHEMA_DEPTH = 32
        private val FUNCTION_CODE = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val REFERENCE_KEYWORDS = listOf("\$ref", "\$dynamicRef")
    }

    private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    fun create(request: CreateFunctionContractRequest): FunctionContractResponse {
        if (!FUNCTION_CODE.matches(request.code)) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }

        return FunctionContractResponse.from(contract = createContract(request = request))
    }

    fun createContract(request: CreateFunctionContractRequest): FunctionContract {
        validateContractVersion(version = request.contractVersion)
        validateSchema(schema = request.inputSchema, input = true, format = request.responseFormat)
        validateSchema(schema = request.outputSchema, input = false, format = request.responseFormat)

        val contract = FunctionContract(
            UUID.randomUUID(),
            request.code,
            request.contractVersion,
            request.name,
            request.description,
            request.responseFormat,
            request.inputSchema,
            request.outputSchema,
        )
        return try {
            functionContractRepository.saveAndFlush(contract)
        } catch (exception: DataIntegrityViolationException) {
            throw DomainClientException(ErrorCode.FUNCTION_CONTRACT_ALREADY_EXISTS)
        }
    }

    fun list(): List<FunctionContractResponse> {
        return functionContractRepository.findAllByOrderByCodeAscContractVersionAsc()
            .map(FunctionContractResponse::from)
    }

    fun get(id: UUID): FunctionContractResponse {
        return FunctionContractResponse.from(contract = requireFunctionContract(id = id))
    }

    fun requireByCode(code: String, contractVersion: String): FunctionContract {
        return findByCode(code = code, contractVersion = contractVersion)
            ?: throw DomainClientException(ErrorCode.FUNCTION_CONTRACT_NOT_FOUND)
    }

    fun findByCode(code: String, contractVersion: String): FunctionContract? {
        return functionContractRepository.findByCodeAndContractVersion(
            code = code,
            contractVersion = contractVersion,
        )
    }

    fun providers(id: UUID): List<FunctionProviderDto> {
        requireFunctionContract(id = id)
        return versionRepository.findAllByFunctionContractIdAndStatus(
            functionContractId = id,
            status = AgentVersionStatus.ACTIVE,
        ).map { version ->
            val agent = agentRepository.findById(version.agentId).orElseThrow {
                DomainClientException(ErrorCode.AGENT_NOT_FOUND)
            }
            FunctionProviderDto(
                agentId = version.agentId,
                agentCode = agent.code,
                agentName = agent.name,
                versionId = version.id,
                semver = version.semver,
                priceAtomic = version.priceAtomic.toString(),
            )
        }.sortedWith(
            compareBy<FunctionProviderDto> { provider -> provider.priceAtomic.toBigInteger() }
                .thenBy { provider -> provider.agentId }
                .thenBy { provider -> provider.versionId },
        )
    }

    fun providerMetrics(id: UUID): List<FunctionProviderMetricResponse> {
        val providers = providers(id = id)
        val metrics = providerMetricService.performance(
            functionContractId = id,
            versionIds = providers.map { provider -> provider.versionId },
        )
        return providers.map { provider ->
            val performance = metrics[provider.versionId]
            FunctionProviderMetricResponse(
                agentId = provider.agentId,
                agentCode = provider.agentCode,
                agentName = provider.agentName,
                versionId = provider.versionId,
                semver = provider.semver,
                priceAtomic = provider.priceAtomic,
                observationCount = performance?.observationCount ?: 0,
                reliabilityPercent = performance?.reliabilityPercent,
                p95LatencyMillis = performance?.p95LatencyMillis,
                contractCompliancePercent = performance?.contractCompliancePercent,
                mature = performance?.isMature ?: false,
            )
        }
    }

    fun requireFunctionContract(id: UUID): FunctionContract {
        return functionContractRepository.findById(id).orElseThrow {
            DomainClientException(ErrorCode.FUNCTION_CONTRACT_NOT_FOUND)
        }
    }

    fun validateInstance(schema: JsonNode, value: JsonNode, errorCode: ErrorCode) {
        val errors = try {
            schemaRegistry.getSchema(schema).validate(value)
        } catch (exception: RuntimeException) {
            throw DomainClientException(errorCode)
        }
        if (errors.isNotEmpty()) {
            throw DomainClientException(errorCode)
        }
    }

    private fun validateContractVersion(version: String) {
        if (runCatching { Semver.parse(version) }.getOrNull() == null) {
            throw DomainClientException(ErrorCode.INVALID_SEMVER)
        }
    }

    private fun validateSchema(schema: JsonNode, input: Boolean, format: AgentResponseFormat) {
        val invalidSize = runCatching { objectMapper.writeValueAsBytes(schema).size > MAX_SCHEMA_BYTES }
            .getOrDefault(true)
        if (!schema.isObject || invalidSize || depth(node = schema) > MAX_SCHEMA_DEPTH || hasRemoteReference(schema)) {
            throw DomainClientException(ErrorCode.INVALID_FUNCTION_CONTRACT_SCHEMA)
        }
        if (input && schema.path("type").asText() != "object") {
            throw DomainClientException(ErrorCode.INVALID_FUNCTION_CONTRACT_SCHEMA)
        }
        if (!input && !matchesFormat(schema = schema, format = format)) {
            throw DomainClientException(ErrorCode.INVALID_FUNCTION_CONTRACT_SCHEMA)
        }
        try {
            schemaRegistry.getSchema(schema)
        } catch (exception: RuntimeException) {
            throw DomainClientException(ErrorCode.INVALID_FUNCTION_CONTRACT_SCHEMA)
        }
    }

    private fun matchesFormat(schema: JsonNode, format: AgentResponseFormat): Boolean {
        return when (format) {
            AgentResponseFormat.TEXT, AgentResponseFormat.MARKDOWN -> schema.path("type").asText() == "string"
            AgentResponseFormat.STRUCTURED -> {
                matchesStructuredFormat(schema = schema)
            }
            AgentResponseFormat.JSON -> true
        }
    }

    private fun matchesStructuredFormat(schema: JsonNode): Boolean {
        val required = schema.path("required").map(JsonNode::asText).toSet()
        val properties = schema.path("properties")
        val title = properties.path("title")
        val summary = properties.get("summary")
        val sections = properties.path("sections")
        val sectionItem = sections.path("items")
        val sectionRequired = sectionItem.path("required").map(JsonNode::asText).toSet()
        val sectionProperties = sectionItem.path("properties")
        return schema.path("type").asText() == "object" &&
            required.containsAll(setOf("title", "sections")) &&
            title.path("type").asText() == "string" && title.path("minLength").asInt() >= 1 &&
            (summary == null || summary.path("type").asText() == "string") &&
            sections.path("type").asText() == "array" && sections.path("minItems").asInt() >= 1 &&
            sectionItem.path("type").asText() == "object" &&
            sectionRequired.containsAll(setOf("label", "value")) &&
            sectionProperties.path("label").path("type").asText() == "string" &&
            sectionProperties.path("label").path("minLength").asInt() >= 1 &&
            declaresScalar(schema = sectionProperties.path("value"))
    }

    private fun declaresScalar(schema: JsonNode): Boolean {
        val allowed = setOf("string", "number", "integer", "boolean")
        val declared = when {
            schema.path("type").isTextual -> setOf(schema.path("type").asText())
            schema.path("type").isArray -> schema.path("type").map(JsonNode::asText).toSet()
            else -> schema.path("oneOf").map { option -> option.path("type").asText() }.toSet()
        }
        return declared.isNotEmpty() && declared.all(allowed::contains)
    }

    private fun hasRemoteReference(node: JsonNode): Boolean {
        if (node.isObject) {
            val containsRemoteReference = REFERENCE_KEYWORDS.any { keyword ->
                val reference = node.path(keyword)
                reference.isTextual && !reference.asText().startsWith("#")
            }
            if (containsRemoteReference) {
                return true
            }
        }
        return node.any { child -> hasRemoteReference(child) }
    }

    private fun depth(node: JsonNode): Int {
        if (!node.isContainerNode || node.isEmpty) {
            return 1
        }
        return 1 + node.maxOf { child -> depth(child) }
    }
}
