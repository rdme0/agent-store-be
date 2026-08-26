package com.agentstore.agent.service

import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateFunctionContractRequest
import com.agentstore.agent.dto.request.DemoCatalogAgentRequest
import com.agentstore.agent.dto.request.DemoCatalogDefinition
import com.agentstore.agent.dto.request.DemoCatalogDependencyRequest
import com.agentstore.agent.dto.request.DemoFunctionContractRequest
import com.agentstore.agent.model.entity.AgentCapability
import com.agentstore.agent.model.entity.Developer
import com.agentstore.agent.model.entity.User
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.repository.UserRepository
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.CreateDependencyRequest
import com.agentstore.dependency.model.vo.ProviderScope
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.service.DependencyService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DemoCatalogRegistrationService(
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val developerRepository: DeveloperRepository,
    private val capabilityService: AgentCapabilityService,
    private val agentService: AgentService,
    private val dependencyService: DependencyService,
) {
    companion object {
        private const val DEMO_USER_EXTERNAL_ID = "agent-store-demo-catalog"
        private const val DEMO_DEVELOPER_NAME = "AgentStore Demo"
        private val DEMO_USER_ID = UUID.fromString("00000000-0000-0000-0000-00000000d000")
        private val DEMO_DEVELOPER_ID = UUID.fromString("00000000-0000-0000-0000-00000000d001")
    }

    private data class ContractKey(
        val code: String,
        val contractVersion: String,
    )

    @Transactional
    fun register(request: DemoCatalogDefinition) {
        requireCatalogShape(request)

        val developer = ensureDeveloper()
        val contracts = request.agents.associate { agent ->
            val contract = ensureContract(agent.functionContract)
            ContractKey(
                code = agent.functionContract.code,
                contractVersion = agent.functionContract.contractVersion,
            ) to contract.id
        }

        val specialists = request.agents.filter { it.dependencies.isEmpty() }
        val roots = request.agents.filter { it.dependencies.isNotEmpty() }
        for (agent in specialists + roots) {
            createAgent(
                request = agent,
                developerId = developer.id,
                contracts = contracts,
            )
        }
    }

    private fun requireCatalogShape(request: DemoCatalogDefinition) {
        if (request.agents.isEmpty() || request.agents.map { it.code }.toSet().size != request.agents.size) {
            conflict()
        }
        val invalidIdentity = request.agents.any { agent ->
            agent.developerId != DEMO_DEVELOPER_ID || agent.developerName != DEMO_DEVELOPER_NAME
        }
        if (invalidIdentity) {
            conflict()
        }
        val conflictingContracts = request.agents
            .groupBy { it.functionContract.code to it.functionContract.contractVersion }
            .values
            .any { definitions -> definitions.map { it.functionContract }.distinct().size > 1 }
        if (conflictingContracts) {
            conflict()
        }
    }

    private fun ensureDeveloper(): Developer {
        val existingDeveloper = developerRepository.findById(DEMO_DEVELOPER_ID).orElse(null)
        if (existingDeveloper != null) {
            val existingUser = existingDeveloper.user
            if (
                existingDeveloper.displayName != DEMO_DEVELOPER_NAME ||
                existingUser.id != DEMO_USER_ID ||
                existingUser.externalId != DEMO_USER_EXTERNAL_ID
            ) {
                conflict()
            }
            return existingDeveloper
        }

        val user = userRepository.findById(DEMO_USER_ID).orElse(null)
            ?: userRepository.save(User(DEMO_USER_ID, DEMO_USER_EXTERNAL_ID))
        if (user.externalId != DEMO_USER_EXTERNAL_ID) {
            conflict()
        }
        return developerRepository.save(Developer(DEMO_DEVELOPER_ID, user, DEMO_DEVELOPER_NAME))
    }

    private fun ensureContract(request: DemoFunctionContractRequest): AgentCapability {
        val input = objectMapper.valueToTree<JsonNode>(request.inputSchema)
        val output = objectMapper.valueToTree<JsonNode>(request.outputSchema)
        val existing = capabilityService.findByCode(code = request.code, contractVersion = request.contractVersion)
        if (existing != null) {
            if (
                existing.name != request.name ||
                existing.description != request.description ||
                existing.responseFormat != request.responseFormat ||
                existing.inputSchema != input ||
                existing.outputSchema != output
            ) {
                conflict()
            }
            return existing
        }
        return capabilityService.create(
            CreateFunctionContractRequest(
                code = request.code,
                contractVersion = request.contractVersion,
                name = request.name,
                description = request.description,
                responseFormat = request.responseFormat,
                inputSchema = input,
                outputSchema = output,
            ),
        )
    }

    private fun createAgent(
        request: DemoCatalogAgentRequest,
        developerId: UUID,
        contracts: Map<ContractKey, UUID>,
    ) {
        val contractId = contracts[contractKey(request.functionContract)] ?: conflict()
        val created = agentService.create(
            CreateAgentRequest(
                developerId = developerId,
                code = request.code,
                name = request.name,
                description = request.description,
                semver = request.semver,
                endpoint = request.endpoint,
                priceAtomic = request.priceAtomic,
                network = request.network,
                asset = request.asset,
                payTo = request.payTo,
                responseFormat = request.responseFormat,
                functionContractId = contractId,
                usageType = request.usageType,
            ),
        )
        val version = created.versions.single { it.semver == request.semver }
        request.dependencies.forEach { dependency ->
            dependencyService.create(
                sourceVersionId = version.id,
                request = dependencyRequest(dependency = dependency, contracts = contracts),
            )
        }
        agentService.publish(version.id)
    }

    private fun dependencyRequest(
        dependency: DemoCatalogDependencyRequest,
        contracts: Map<ContractKey, UUID>,
    ): CreateDependencyRequest {
        return CreateDependencyRequest(
            functionContractId = contracts[
                ContractKey(
                    code = dependency.functionCode,
                    contractVersion = dependency.contractVersion,
                )
            ] ?: conflict(),
            providerScope = ProviderScope.MARKETPLACE,
            selectionStrategy = ProviderSelectionStrategy.LOWEST_PRICE,
            versionConstraint = dependency.versionConstraint,
            required = true,
            maxPriceAtomic = dependency.maxPriceAtomic,
            maxCalls = 1,
        )
    }

    private fun contractKey(request: DemoFunctionContractRequest): ContractKey {
        return ContractKey(code = request.code, contractVersion = request.contractVersion)
    }

    private fun conflict(): Nothing {
        throw DomainClientException(ErrorCode.DATA_INTEGRITY_CONFLICT)
    }
}
