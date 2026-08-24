package com.agentstore.agent.service

import com.agentstore.agent.dto.request.AgentManifestRequest
import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.response.AgentManifestImportResponse
import com.agentstore.agent.dto.response.AgentManifestResponse
import com.agentstore.agent.dto.response.AgentManifestValidationResponse
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.entity.AgentCapability
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentUsageType
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.service.DependencyService
import jakarta.transaction.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

@Service
class AgentManifestService(
    private val agentService: AgentService,
    private val functionContractService: AgentCapabilityService,
    private val dependencyService: DependencyService,
) {
    companion object {
        private const val API_VERSION = "agentstore/v1"
        private val AGENT_CODE = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
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
    fun import(request: AgentManifestRequest): AgentManifestImportResponse {
        val manifest = parse(content = request.content)
        if (agentService.findBySlug(manifest.agentCode) != null) {
            throw DomainClientException(ErrorCode.AGENT_ALREADY_EXISTS)
        }
        val contract = functionContractService.requireByCode(
            code = manifest.functionCode,
            contractVersion = manifest.functionVersion,
        )
        val created = agentService.create(
            request = CreateAgentRequest(
                developerId = manifest.developerId,
                slug = manifest.agentCode,
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
            agentCode = created.slug,
            sha256 = manifest.sha256,
        )
    }

    @Transactional
    fun replace(versionId: UUID, request: AgentManifestRequest): AgentManifestResponse {
        val manifest = parse(content = request.content)
        val version = agentService.requireVersion(versionId)
        if (version.status != AgentVersionStatus.DRAFT) {
            throw DomainClientException(ErrorCode.ACTIVE_VERSION_IMMUTABLE)
        }
        val agent = agentService.requireAgent(version.agentId)
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
                        agentService.findBySlug(code)?.id
                            ?: throw DomainClientException(ErrorCode.AGENT_NOT_FOUND)
                    },
                    functionContractId = dependencyContract.id,
                    providerScope = dependency.providerScope,
                    selectionStrategy = dependency.selectionStrategy,
                    allowedProviderAgentIds = dependency.allowedAgentCodes.map { code ->
                        agentService.findBySlug(code)?.id
                            ?: throw DomainClientException(ErrorCode.AGENT_NOT_FOUND)
                    }.toSet(),
                    minReliabilityPercent = dependency.minReliabilityPercent,
                    maxP95LatencyMillis = dependency.maxP95LatencyMillis,
                    explorationPercent = dependency.explorationPercent,
                    reliabilityWeight = dependency.reliabilityWeight,
                    priceWeight = dependency.priceWeight,
                    speedWeight = dependency.speedWeight,
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
        contract: AgentCapability,
    ) {
        val unchanged = manifest.developerId == agent.developerId &&
            manifest.agentCode == agent.slug &&
            manifest.agentName == agent.name &&
            manifest.agentDescription == agent.description &&
            manifest.usageType == agent.usageType &&
            manifest.agentVersion == version.semver &&
            version.capabilityId == contract.id &&
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
        val root = readYaml(content = content)
        requireAllowedKeys(
            root = root,
            allowed = setOf("apiVersion", "agent", "dependencies"),
        )
        requireString(root = root, field = "apiVersion", expected = API_VERSION)
        val agent = requireMap(root = root, field = "agent")
        val function = requireMap(root = agent, field = "function")
        val payment = requireMap(root = agent, field = "payment")
        requireAllowedKeys(
            root = agent,
            allowed = setOf(
                "developerId",
                "code",
                "name",
                "description",
                "version",
                "usageType",
                "function",
                "endpoint",
                "payment",
            ),
        )
        requireAllowedKeys(root = function, allowed = setOf("code", "version"))
        requireAllowedKeys(
            root = payment,
            allowed = setOf("priceAtomic", "network", "asset", "payTo"),
        )
        val dependencies = optionalList(root = root, field = "dependencies").map { value ->
            parseDependency(root = requireMapValue(value = value, field = "dependencies"))
        }
        val agentCode = requireString(root = agent, field = "code")
        if (!AGENT_CODE.matches(agentCode)) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        val functionCode = requireString(root = function, field = "code")
        if (!AGENT_CODE.matches(functionCode)) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        val canonical = writeYaml(root = root)
        return ParsedManifestDto(
            canonicalContent = canonical,
            sha256 = sha256(content = canonical),
            developerId = UUID.fromString(requireString(root = agent, field = "developerId")),
            agentCode = agentCode,
            agentName = requireString(root = agent, field = "name"),
            agentDescription = requireString(root = agent, field = "description"),
            agentVersion = requireString(root = agent, field = "version"),
            usageType = AgentUsageType.from(requireString(root = agent, field = "usageType")),
            functionCode = functionCode,
            functionVersion = requireString(root = function, field = "version"),
            endpoint = requireString(root = agent, field = "endpoint"),
            priceAtomic = requireString(root = payment, field = "priceAtomic"),
            network = requireString(root = payment, field = "network"),
            asset = requireString(root = payment, field = "asset"),
            payTo = requireString(root = payment, field = "payTo"),
            dependencies = dependencies,
        )
    }

    private fun parseDependency(root: Map<String, Any?>): ParsedDependencyDto {
        val function = requireMap(root = root, field = "function")
        val providers = requireMap(root = root, field = "providers")
        val constraints = requireMap(root = root, field = "constraints")
        val resolution = requireMap(root = root, field = "resolution")
        requireAllowedKeys(root = root, allowed = setOf("function", "providers", "constraints", "resolution"))
        requireAllowedKeys(root = function, allowed = setOf("code", "version"))
        requireAllowedKeys(
            root = providers,
            allowed = setOf("scope", "pinnedAgentCode", "allowedAgentCodes"),
        )
        requireAllowedKeys(
            root = constraints,
            allowed = setOf(
                "versionConstraint",
                "required",
                "maxPriceAtomic",
                "maxCalls",
                "minReliabilityPercent",
                "maxP95LatencyMillis",
            ),
        )
        requireAllowedKeys(
            root = resolution,
            allowed = setOf("strategy", "explorationPercent", "weights"),
        )
        val providerScope = ProviderScope.valueOf(
            requireString(root = providers, field = "scope").uppercase(),
        )
        val strategy = optionalString(root = resolution, field = "strategy")
            ?.let(ProviderSelectionStrategy::from)
        val weights = resolution["weights"]?.let { value -> requireMapValue(value = value, field = "weights") }
        weights?.let { value ->
            requireAllowedKeys(root = value, allowed = setOf("reliability", "price", "speed"))
        }
        val allowedCodes = optionalList(root = providers, field = "allowedAgentCodes")
            .map { value -> value as? String ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE) }
        val pinnedCode = optionalString(root = providers, field = "pinnedAgentCode")
        return ParsedDependencyDto(
            functionCode = requireString(root = function, field = "code"),
            functionVersion = requireString(root = function, field = "version"),
            providerScope = providerScope,
            selectionStrategy = strategy,
            pinnedAgentCode = pinnedCode,
            allowedAgentCodes = allowedCodes,
            minReliabilityPercent = optionalInt(root = constraints, field = "minReliabilityPercent"),
            maxP95LatencyMillis = optionalInt(root = constraints, field = "maxP95LatencyMillis"),
            explorationPercent = requireInt(root = resolution, field = "explorationPercent"),
            reliabilityWeight = optionalInt(root = weights, field = "reliability"),
            priceWeight = optionalInt(root = weights, field = "price"),
            speedWeight = optionalInt(root = weights, field = "speed"),
            versionConstraint = requireString(root = constraints, field = "versionConstraint"),
            required = requireBoolean(root = constraints, field = "required"),
            maxPriceAtomic = requireString(root = constraints, field = "maxPriceAtomic"),
            maxCalls = requireInt(root = constraints, field = "maxCalls"),
        )
    }

    private fun readYaml(content: String): Map<String, Any?> {
        val options = LoaderOptions()
        options.maxAliasesForCollections = 0
        options.codePointLimit = 262_144
        options.nestingDepthLimit = 32
        options.isAllowDuplicateKeys = false
        val value = try {
            Yaml(SafeConstructor(options)).load<Any?>(content)
        } catch (exception: YAMLException) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
            ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        return requireMapValue(value = value, field = "root")
    }

    private fun writeYaml(root: Map<String, Any?>): String {
        val options = DumperOptions()
        options.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        options.indent = 2
        options.indicatorIndent = 1
        options.isPrettyFlow = true
        return Yaml(options).dump(root).replace("\r\n", "\n")
    }

    private fun requireMap(root: Map<String, Any?>, field: String): Map<String, Any?> {
        return requireMapValue(value = root[field], field = field)
    }

    private fun requireAllowedKeys(root: Map<String, Any?>, allowed: Set<String>) {
        if (root.keys.any { key -> key !in allowed }) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun requireMapValue(value: Any?, field: String): Map<String, Any?> {
        val map = value as? Map<*,*> ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        return map.entries.associate { entry ->
            val key = entry.key as? String ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
            key to entry.value
        }
    }

    private fun requireString(root: Map<String, Any?>, field: String, expected: String? = null): String {
        val value = root[field] as? String ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        if (expected != null && value != expected) {
            throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
        return value
    }

    private fun optionalString(root: Map<String, Any?>, field: String): String? {
        return root[field]?.let { value ->
            value as? String ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun optionalList(root: Map<String, Any?>, field: String): List<Any?> {
        return root[field]?.let { value ->
            value as? List<*> ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
        }
            ?: emptyList()
    }

    private fun requireInt(root: Map<String, Any?>, field: String): Int {
        return optionalInt(root = root, field = field)
            ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
    }

    private fun optionalInt(root: Map<String, Any?>?, field: String): Int? {
        if (root == null) {
            return null
        }
        return when (val value = root[field]) {
            is Int -> value
            is Long -> value.toInt().takeIf { converted -> converted.toLong() == value }
            else -> null
        }
    }

    private fun requireBoolean(root: Map<String, Any?>, field: String): Boolean {
        return root[field] as? Boolean ?: throw DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
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
    val explorationPercent: Int,
    val reliabilityWeight: Int?,
    val priceWeight: Int?,
    val speedWeight: Int?,
    val versionConstraint: String,
    val required: Boolean,
    val maxPriceAtomic: String,
    val maxCalls: Int,
)
