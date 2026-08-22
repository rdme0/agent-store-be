package com.agentstore.dependency.service

import com.agentstore.agent.exception.AgentNotFoundException
import com.agentstore.agent.resolver.AgentEndpointPolicy
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.model.entity.ExecutionQuote
import com.agentstore.dependency.model.vo.ResolvedNode
import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.dependency.resolver.CostResolver
import com.agentstore.dependency.resolver.DependencyResolver
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
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
        val root = candidates.filter { candidate ->
            matches(version = candidate.semver, constraint = constraint)
        }
            .maxByOrNull { versionKey(it.semver) }
            ?: throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        val graph = resolver.resolve(
            rootVersionId = root.id,
            allowUnresolvedRequired = false,
            allowPriceExceeded = false,
        )
        validateEndpoints(graph.root)
        val cost = costResolver.resolve(graph.root)
        val snapshot = graph.root.snapshot()
        val expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES)
        val quote = quoteRepository.save(
            ExecutionQuote(
                UUID.randomUUID(),
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
            snapshot = snapshot,
            warnings = graph.warnings,
        )
    }

    private fun matches(version: String, constraint: String): Boolean {
        return when {
            constraint == "*" -> true
            constraint.startsWith("^") -> matchesCaret(version = version, constraint = constraint)
            constraint.startsWith("~") -> matchesTilde(version = version, constraint = constraint)
            else -> version == constraint
        }
    }

    private fun matchesCaret(version: String, constraint: String): Boolean {
        val minimumVersion = constraint.drop(1)
        return versionKey(value = version) >= versionKey(value = minimumVersion) &&
            version.substringBefore('.') == minimumVersion.substringBefore('.')
    }

    private fun matchesTilde(version: String, constraint: String): Boolean {
        val minimumVersion = constraint.drop(1)
        val versionMinor = version.substringBeforeLast('.').substringBeforeLast('.')
        val minimumMinor = minimumVersion.substringBeforeLast('.').substringBeforeLast('.')

        return versionKey(value = version) >= versionKey(value = minimumVersion) &&
            versionMinor == minimumMinor
    }

    private fun versionKey(value: String): Long {
        val parts = value.removePrefix("^").removePrefix("~").split(".")
        return parts[0].toLong() * 1_000_000L + parts[1].toLong() * 1_000L + parts[2].takeWhile { it.isDigit() }
            .toLong()
    }

    private fun validateEndpoints(node: ResolvedNode) {
        endpointPolicy.validate(node.version.endpoint)
        node.dependencies.mapNotNull { it.resolved }.forEach(::validateEndpoints)
    }
}
