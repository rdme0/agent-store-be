package com.agentstore.dependency.service

import com.agentstore.agent.exception.AgentNotFoundException
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.model.entity.ExecutionQuote
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.dependency.resolver.CostResolver
import com.agentstore.dependency.resolver.DependencyResolver
import com.agentstore.payment.dto.response.KrwEstimateResponse
import com.agentstore.payment.service.KrwEstimateService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class QuoteService(
    private val agentService: AgentService,
    private val quoteRepository: ExecutionQuoteRepository,
    private val resolver: DependencyResolver,
    private val costResolver: CostResolver,
    private val endpointPolicy: AgentEndpointPolicy,
    private val objectMapper: ObjectMapper,
    private val krwEstimateService: KrwEstimateService,
) {
    fun requireQuote(id: UUID): ExecutionQuote {
        return quoteRepository.findById(id).orElseThrow {
            DomainClientException(ErrorCode.QUOTE_NOT_FOUND)
        }
    }

    fun findQuoteOrNull(id: UUID): ExecutionQuote? {
        return quoteRepository.findById(id).orElse(null)
    }

    fun snapshot(id: UUID): JsonNode {
        return requireQuote(id).snapshot
    }

    @Transactional
    fun create(slug: String, request: QuoteRequest): QuoteResponse {
        val constraint = request.versionConstraint ?: "*"
        resolver.validateConstraint(constraint)
        val agent = agentService.findBySlug(slug) ?: throw AgentNotFoundException()
        val candidates = agentService.activeVersions(agent.id)
        if (candidates.isEmpty()) {
            throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        }
        val root = resolver.newest(
            versions = candidates.filter { candidate ->
                resolver.matches(version = candidate.semver, constraint = constraint)
            },
        )
            ?: throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        val quoteId = UUID.randomUUID()
        val graph = resolver.resolve(
            rootVersionId = root.id,
            selectionSeed = quoteId,
            allowUnresolvedRequired = false,
            allowPriceExceeded = false,
        )
        validateEndpoints(graph.root)
        val cost = costResolver.resolve(graph.root)
        val estimate = krwEstimateService.estimate(amountAtomic = cost.maxCostAtomic)
        val snapshot = graph.root.snapshot().copy(krwEstimate = estimate)
        val expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES)
        val quote = quoteRepository.save(
            ExecutionQuote(
                quoteId,
                root.id,
                expiresAt,
                cost.maxCostAtomic,
                objectMapper.valueToTree(snapshot)
            )
        )
        return QuoteResponse(
            id = quote.id,
            rootVersionId = root.id,
            expiresAt = expiresAt,
            maxCostAtomic = cost.maxCostAtomic.toString(),
            maxCostKrwEstimate = estimate?.let(KrwEstimateResponse::from),
            snapshot = snapshot,
            warnings = graph.warnings,
        )
    }

    @Transactional
    fun createFunction(
        functionCode: String,
        contractVersion: String,
        strategy: ProviderSelectionStrategy,
        maxTotalAtomic: BigInteger,
    ): QuoteResponse {
        val quoteId = UUID.randomUUID()
        val graph = resolver.resolveFunctionRoot(
            functionCode = functionCode,
            contractVersion = contractVersion,
            strategy = strategy,
            maxPriceAtomic = maxTotalAtomic,
            selectionSeed = quoteId,
        )
        validateEndpoints(node = graph.root)
        val cost = costResolver.resolve(graph.root)
        if (cost.maxCostAtomic > maxTotalAtomic) {
            throw DomainClientException(ErrorCode.DEPENDENCY_PRICE_EXCEEDED)
        }
        val estimate = krwEstimateService.estimate(amountAtomic = cost.maxCostAtomic)
        val snapshot = graph.root.snapshot().copy(krwEstimate = estimate)
        val expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES)
        val quote = quoteRepository.save(
            ExecutionQuote(
                quoteId,
                graph.root.version.id,
                expiresAt,
                cost.maxCostAtomic,
                objectMapper.valueToTree(snapshot),
            )
        )
        return QuoteResponse(
            id = quote.id,
            rootVersionId = quote.rootVersionId,
            expiresAt = expiresAt,
            maxCostAtomic = cost.maxCostAtomic.toString(),
            maxCostKrwEstimate = estimate?.let(KrwEstimateResponse::from),
            snapshot = snapshot,
            warnings = graph.warnings,
        )
    }

    private fun validateEndpoints(node: ResolvedNode) {
        endpointPolicy.validate(node.version.endpoint)
        node.dependencies.mapNotNull { it.resolved }.forEach(::validateEndpoints)
    }
}
