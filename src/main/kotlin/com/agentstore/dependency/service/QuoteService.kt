package com.agentstore.dependency.service

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.web.ApiException
import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.repository.ExecutionQuoteRepository
import com.agentstore.dependency.resolver.CostResolver
import com.agentstore.dependency.resolver.DependencyResolver
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class QuoteService(
    private val agentRepository: AgentRepository,
    private val agentVersionRepository: AgentVersionRepository,
    private val quoteRepository: ExecutionQuoteRepository,
    private val resolver: DependencyResolver,
    private val costResolver: CostResolver,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun create(slug: String, request: QuoteRequest): QuoteResponse {
        val constraint = request.versionConstraint ?: "*"
        resolver.validateConstraint(constraint)
        val agent = agentRepository.findBySlug(slug) ?: throw ApiException("AGENT_NOT_FOUND", "Agent was not found", 404, mapOf("slug" to slug))
        val candidates = agentVersionRepository.findAllByAgentIdAndStatus(agent.id, AgentVersionStatus.ACTIVE)
        if (candidates.isEmpty()) throw ApiException("AGENT_VERSION_NOT_FOUND", "No ACTIVE Agent version satisfies the requested constraint", 409, mapOf("slug" to slug))
        val root = candidates.filter { matches(it.semver, constraint) }.maxByOrNull { versionKey(it.semver) }
            ?: throw ApiException("AGENT_VERSION_NOT_FOUND", "No ACTIVE Agent version satisfies the requested constraint", 409, mapOf("slug" to slug, "versionConstraint" to constraint))
        val graph = resolver.resolve(root.id)
        val cost = costResolver.resolve(graph.root)
        val snapshot = graph.root.snapshot()
        val expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES)
        val quote = quoteRepository.save(com.agentstore.dependency.model.entity.ExecutionQuote(UUID.randomUUID(), root.id, expiresAt, cost.maxCostAtomic, objectMapper.valueToTree(snapshot)))
        return QuoteResponse(quote.id, root.id, expiresAt, cost.maxCostAtomic.toString(), snapshot, graph.warnings)
    }

    private fun matches(version: String, constraint: String): Boolean = when {
        constraint == "*" -> true
        constraint.startsWith("^") -> versionKey(version) >= versionKey(constraint.drop(1)) && version.substringBefore('.') == constraint.drop(1).substringBefore('.')
        constraint.startsWith("~") -> versionKey(version) >= versionKey(constraint.drop(1)) && version.substringBeforeLast('.').substringBeforeLast('.') == constraint.drop(1).substringBeforeLast('.').substringBeforeLast('.')
        else -> version == constraint
    }

    private fun versionKey(value: String): Long {
        val parts = value.removePrefix("^").removePrefix("~").split(".")
        return parts[0].toLong() * 1_000_000L + parts[1].toLong() * 1_000L + parts[2].takeWhile { it.isDigit() }.toLong()
    }
}
