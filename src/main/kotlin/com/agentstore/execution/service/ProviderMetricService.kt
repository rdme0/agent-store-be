package com.agentstore.execution.service

import com.agentstore.execution.model.entity.AgentInvocationObservation
import com.agentstore.execution.model.vo.AgentInvocationOutcome
import com.agentstore.execution.repository.AgentInvocationObservationRepository
import jakarta.transaction.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

data class ProviderPerformanceDto(
    val observationCount: Int,
    val reliabilityPercent: Int?,
    val p95LatencyMillis: Long?,
    val contractCompliancePercent: Int?,
) {
    companion object {
        const val MINIMUM_OBSERVATIONS = 20
    }

    val isMature: Boolean
        get() {
            return observationCount >= MINIMUM_OBSERVATIONS && reliabilityPercent != null &&
                p95LatencyMillis != null
        }
}

@Service
class ProviderMetricService(
    private val observationRepository: AgentInvocationObservationRepository,
    private val clock: Clock,
) {
    companion object {
        private const val WINDOW_DAYS = 30L
        private const val WILSON_Z = 1.959963984540054
    }

    @Transactional
    fun start(stepId: UUID, agentVersionId: UUID, functionContractId: UUID?) {
        if (observationRepository.findByExecutionStepId(stepId) != null) {
            return
        }
        val observation = AgentInvocationObservation(
            UUID.randomUUID(),
            stepId,
            agentVersionId,
            functionContractId,
            clock.instant(),
        )
        try {
            observationRepository.saveAndFlush(observation)
        } catch (exception: DataIntegrityViolationException) {
            return
        }
    }

    @Transactional
    fun finish(stepId: UUID, outcome: AgentInvocationOutcome) {
        val observation = observationRepository.findByExecutionStepId(stepId) ?: return
        observation.finish(outcome, clock.instant())
        observationRepository.save(observation)
    }

    fun performance(functionContractId: UUID, versionIds: Collection<UUID>): Map<UUID, ProviderPerformanceDto> {
        if (versionIds.isEmpty()) {
            return emptyMap()
        }
        val cutoff = clock.instant().minus(Duration.ofDays(WINDOW_DAYS))
        val observations = observationRepository
            .findAllByFunctionContractIdAndCompletedAtGreaterThanEqual(
                functionContractId = functionContractId,
                completedAt = cutoff,
            )
            .filter { observation -> observation.agentVersionId in versionIds && observation.outcome != null }
        return versionIds.associateWith { versionId ->
            calculate(observations = observations.filter { observation -> observation.agentVersionId == versionId })
        }
    }

    private fun calculate(observations: List<AgentInvocationObservation>): ProviderPerformanceDto {
        val attributable = observations.filter { observation ->
            observation.outcome in attributableOutcomes()
        }
        if (attributable.isEmpty()) {
            return ProviderPerformanceDto(
                observationCount = 0,
                reliabilityPercent = null,
                p95LatencyMillis = null,
                contractCompliancePercent = null,
            )
        }
        val successes = attributable.count { observation -> observation.outcome == AgentInvocationOutcome.SUCCESS }
        val compliant = attributable.count { observation ->
            observation.outcome != AgentInvocationOutcome.OUTPUT_FORMAT_INVALID &&
                observation.outcome != AgentInvocationOutcome.OUTPUT_SCHEMA_INVALID
        }
        val latencies = attributable
            .filter { observation -> observation.outcome == AgentInvocationOutcome.SUCCESS }
            .mapNotNull(AgentInvocationObservation::getLatencyMillis)
            .sorted()
        return ProviderPerformanceDto(
            observationCount = attributable.size,
            reliabilityPercent = percent(
                value = wilsonLowerBound(
                    successes = successes,
                    total = attributable.size,
                ),
            ),
            p95LatencyMillis = percentile95(latencies = latencies),
            contractCompliancePercent = percent(value = compliant.toDouble() / attributable.size),
        )
    }

    private fun attributableOutcomes(): Set<AgentInvocationOutcome> {
        return setOf(
            AgentInvocationOutcome.SUCCESS,
            AgentInvocationOutcome.AGENT_HTTP_FAILURE,
            AgentInvocationOutcome.OUTPUT_FORMAT_INVALID,
            AgentInvocationOutcome.OUTPUT_SCHEMA_INVALID,
        )
    }

    private fun wilsonLowerBound(successes: Int, total: Int): Double {
        if (total == 0) {
            return 0.0
        }
        val sample = successes.toDouble() / total
        val zSquared = WILSON_Z * WILSON_Z
        val denominator = 1.0 + zSquared / total
        val center = sample + zSquared / (2.0 * total)
        val margin = WILSON_Z * sqrt(
            (sample * (1.0 - sample) + zSquared / (4.0 * total)) / total,
        )
        return max(
            a = 0.0,
            b = (center - margin) / denominator,
        )
    }

    private fun percentile95(latencies: List<Long>): Long? {
        if (latencies.isEmpty()) {
            return null
        }
        val index = ceil(latencies.size * 0.95).toInt() - 1
        return latencies[index]
    }

    private fun percent(value: Double): Int {
        return (value * 100.0).toInt()
    }
}
