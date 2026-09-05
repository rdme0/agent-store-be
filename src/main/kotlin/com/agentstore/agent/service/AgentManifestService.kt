package com.agentstore.agent.service

import com.agentstore.agent.config.AgentManifestConfiguration
import com.agentstore.agent.dto.internal.AgentManifestDto
import com.agentstore.agent.dto.request.AgentManifestRequest
import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.response.AgentManifestImportResponse
import com.agentstore.agent.dto.response.AgentManifestResponse
import com.agentstore.agent.dto.response.AgentManifestValidationResponse
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.FunctionContract
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentUsageType
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.service.DependencyService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Validator
import jakarta.transaction.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Qualifier

@Service
class AgentManifestService(
    private val agentService: AgentService,
    private val functionContractService: FunctionContractService,
    private val dependencyService: DependencyService,
    @param:Qualifier(AgentManifestConfiguration.MANIFEST_YAML_MAPPER)
    private val yamlMapper: ObjectMapper,
    private val validator: Validator,
) {
    companion object {
        private const val API_VERSION = "agentstore/v1"
    }

    fun validate(request: AgentManifestRequest): AgentManifestValidationResponse {
        val manifest = parse(content = request.content)
        return AgentManifestValidationResponse(
            canonicalContent = manifest.canonicalContent,
            sha256 = manifest.sha256,
            agentCode = manifest.agentCode,
            functionCode = manifest.functionCode,
        )
    }

    @Transactional
    fun import(request: AgentManifestRequest, developerId: UUID): AgentManifestImportResponse {
        val manifest = parse(content = request.content)
        if (manifest.developerId != developerId) {
            throw DomainClientException(ErrorCode.DEMO_ACCESS_DENIED)
        }
        if (agentService.findByCode(manifest.agentCode) != null) {
            throw DomainClientException(ErrorCode.AGENT_ALREADY_EXISTS)
        }
        val contract = functionContractService.requireByCode(
            code = manifest.functionCode,
            contractVersion = manifest.functionVersion,
        )
        val created = agentService.create(
            request = CreateAgentRequest(
                developerId = developerId,
                code = manifest.agentCode,
                name = manifest.agentName,
                description = manifest.agentDescription,
                semver = manifest.agentVersion,
                endpoint = manifest.endpoint,
                priceAtomic = manifest.priceAtomic,
                network = manifest.network,
                asset = manifest.asset,
                payTo = manifest.payTo,
                responseFormat = contract.responseFormat,
                functionContractId = contract.id,
                verificationInput = manifest.verificationInput,
                usageType = manifest.usageType,
            ),
        )
        val version = created.versions.single { response -> response.semver == manifest.agentVersion }
        createDependencies(
            sourceVersionId = version.id,
            dependencies = manifest.dependencies,
        )
        agentService.attachDraftManifest(
            versionId = version.id,
            content = manifest.canonicalContent,
            sha256 = manifest.sha256,
        )
        return AgentManifestImportResponse(
            agentId = created.id,
            versionId = version.id,
            agentCode = created.code,
            sha256 = manifest.sha256,
        )
    }

    @Transactional
    fun import(request: AgentManifestRequest): AgentManifestImportResponse {
        val manifest = parse(content = request.content)
        return import(request = request, developerId = manifest.developerId)
    }

    @Transactional
    fun replace(versionId: UUID, request: AgentManifestRequest, developerId: UUID): AgentManifestResponse {
        val manifest = parse(content = request.content)
        val version = agentService.requireVersion(versionId)
        if (version.status != AgentVersionStatus.DRAFT) {
            throw DomainClientException(ErrorCode.ACTIVE_VERSION_IMMUTABLE)
        }
        val agent = agentService.requireAgent(version.agentId)
        if (agent.developerId != developerId) {
            throw DomainClientException(ErrorCode.DEMO_ACCESS_DENIED)
        }
        val contract = functionContractService.requireByCode(
            code = manifest.functionCode,
            contractVersion = manifest.functionVersion,
        )
        requireSameDraftIdentity(
            manifest = manifest,
            agent = agent,
            version = version,
            contract = contract,
        )
        dependencyService.list(sourceVersionId = versionId).forEach { dependency ->
            dependencyService.remove(
                sourceVersionId = versionId,
                dependencyId = dependency.id,
            )
        }
        createDependencies(
            sourceVersionId = versionId,
            dependencies = manifest.dependencies,
        )
        agentService.attachDraftManifest(
            versionId = versionId,
            content = manifest.canonicalContent,
            sha256 = manifest.sha256,
        )
        return AgentManifestResponse(
            versionId = versionId,
            content = manifest.canonicalContent,
            sha256 = manifest.sha256,
        )
    }

    @Transactional
    fun replace(versionId: UUID, request: AgentManifestRequest): AgentManifestResponse {
        val manifest = parse(content = request.content)
        return replace(versionId = versionId, request = request, developerId = manifest.developerId)
    }

    private fun createDependencies(
        sourceVersionId: UUID,
        dependencies: List<ParsedDependencyDto>,
    ) {
        dependencies.forEach { dependency ->
            val dependencyContract = functionContractService.requireByCode(
                code = dependency.functionCode,
                contractVersion = dependency.functionVersion,
            )
            dependencyService.create(
                sourceVersionId = sourceVersionId,
                request = CreateDependencyRequest(
                    targetAgentId = dependency.pinnedAgentCode?.let { code ->
                        agentService.findByCode(code)?.id
                            ?: throw DomainClientException(ErrorCode.AGENT_NOT_FOUND)
                    },
                    functionContractId = dependencyContract.id,
                    providerScope = dependency.providerScope,
                    selectionStrategy = dependency.selectionStrategy,
                    allowedProviderAgentIds = dependency.allowedAgentCodes.map { code ->
                        agentService.findByCode(code)?.id
                            ?: throw DomainClientException(ErrorCode.AGENT_NOT_FOUND)
                    }.toSet(),
                    minReliabilityPercent = dependency.minReliabilityPercent,
                    maxP95LatencyMillis = dependency.maxP95LatencyMillis,
                    versionConstraint = dependency.versionConstraint,
                    required = dependency.required,
                    maxPriceAtomic = dependency.maxPriceAtomic,
                    maxCalls = dependency.maxCalls,
                ),
            )
        }
    }

    fun export(versionId: UUID): AgentManifestResponse {
        val manifest = agentService.manifest(versionId = versionId)
            ?: throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        return AgentManifestResponse(
            versionId = versionId,
            content = manifest.first,
            sha256 = manifest.second,
        )
    }

    private fun requireSameDraftIdentity(
        manifest: ParsedManifestDto,
        agent: Agent,
        version: AgentVersion,
        contract: FunctionContract,
    ) {
        val unchanged = manifest.developerId == agent.developerId &&
            manifest.agentCode == agent.code &&
            manifest.agentName == agent.name &&
            manifest.agentDescription == agent.description &&
            manifest.usageType == agent.usageType &&
            manifest.agentVersion == version.semver &&
            version.functionContractId == contract.id &&
            version.responseFormat == contract.responseFormat &&
            manifest.endpoint == version.endpoint &&
            manifest.priceAtomic == version.priceAtomic.toString() &&
            manifest.network == version.network &&
            manifest.asset == version.asset &&
            manifest.payTo == version.payTo
        if (!unchanged) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun parse(content: String): ParsedManifestDto {
        val manifest = readManifest(content = content)
        val violations = validator.validate(manifest)
        if (violations.isNotEmpty() || manifest.apiVersion != API_VERSION) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }

        val canonical = writeManifest(manifest = manifest)
        val agent = manifest.agent
        return ParsedManifestDto(
            canonicalContent = canonical,
            sha256 = sha256(content = canonical),
            developerId = agent.developerId,
            agentCode = agent.code,
            agentName = agent.name,
            agentDescription = agent.description,
            agentVersion = agent.version,
            usageType = agent.usageType,
            functionCode = agent.function.code,
            functionVersion = agent.function.version,
            endpoint = agent.endpoint,
            priceAtomic = agent.payment.priceAtomic,
            network = agent.payment.network,
            asset = agent.payment.asset,
            payTo = agent.payment.payTo,
            verificationInput = agent.verificationInput,
            dependencies = manifest.dependencies.map { dependency ->
                ParsedDependencyDto(
                    functionCode = dependency.function.code,
                    functionVersion = dependency.function.version,
                    providerScope = dependency.providers.scope,
                    selectionStrategy = dependency.resolution.strategy,
                    pinnedAgentCode = dependency.providers.pinnedAgentCode,
                    allowedAgentCodes = dependency.providers.allowedAgentCodes,
                    minReliabilityPercent = dependency.constraints.minReliabilityPercent,
                    maxP95LatencyMillis = dependency.constraints.maxP95LatencyMillis,
                    versionConstraint = dependency.constraints.versionConstraint,
                    required = dependency.constraints.required,
                    maxPriceAtomic = dependency.constraints.maxPriceAtomic,
                    maxCalls = dependency.constraints.maxCalls,
                )
            },
        )
    }

    private fun readManifest(content: String): AgentManifestDto {
        return try {
            yamlMapper.readValue(content, AgentManifestDto::class.java)
        } catch (exception: Exception) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun writeManifest(manifest: AgentManifestDto): String {
        return yamlMapper.writeValueAsString(manifest).replace(
            oldValue = "\r\n",
            newValue = "\n",
        )
    }

    private fun sha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private data class ParsedManifestDto(
    val canonicalContent: String,
    val sha256: String,
    val developerId: UUID,
    val agentCode: String,
    val agentName: String,
    val agentDescription: String,
    val agentVersion: String,
    val usageType: AgentUsageType,
    val functionCode: String,
    val functionVersion: String,
    val endpoint: String,
    val priceAtomic: String,
    val network: String,
    val asset: String,
    val payTo: String,
    val verificationInput: JsonNode?,
    val dependencies: List<ParsedDependencyDto>,
)

private data class ParsedDependencyDto(
    val functionCode: String,
    val functionVersion: String,
    val providerScope: ProviderScope,
    val selectionStrategy: ProviderSelectionStrategy?,
    val pinnedAgentCode: String?,
    val allowedAgentCodes: List<String>,
    val minReliabilityPercent: Int?,
    val maxP95LatencyMillis: Int?,
    val versionConstraint: String,
    val required: Boolean,
    val maxPriceAtomic: String,
    val maxCalls: Int,
)
