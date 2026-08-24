package com.agentstore.payment.service

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.payment.client.BithumbKrwRateClient
import com.agentstore.payment.dto.internal.KrwEstimateDto
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Service

@Service
class KrwEstimateService(
    private val bithumbClient: BithumbKrwRateClient,
    private val properties: AgentStoreProperties,
    private val clock: Clock,
) {
    companion object {
        private val USDC_ATOMIC_UNITS = BigDecimal("1000000")
    }

    private var cachedRate: CachedRate? = null

    @Synchronized
    fun estimate(amountAtomic: BigInteger): KrwEstimateDto? {
        val now = clock.instant()
        val rate = freshRate(now) ?: staleRate(now) ?: return null
        return estimateAtRate(
            amountAtomic = amountAtomic,
            rate = rate.value,
            rateAsOf = rate.asOf,
            stale = !now.isBefore(rate.asOf.plus(properties.bithumbCacheTtl)),
        )
    }

    fun estimateAtRate(amountAtomic: BigInteger, estimate: KrwEstimateDto): KrwEstimateDto? {
        val rate = estimate.rateWonPerUsdc.toBigDecimalOrNull() ?: return null
        return estimateAtRate(
            amountAtomic = amountAtomic,
            rate = rate,
            rateAsOf = estimate.rateAsOf,
            stale = estimate.stale,
        )
    }

    private fun estimateAtRate(
        amountAtomic: BigInteger,
        rate: BigDecimal,
        rateAsOf: Instant,
        stale: Boolean,
    ): KrwEstimateDto {
        val amountWon = BigDecimal(amountAtomic)
            .multiply(rate)
            .divide(USDC_ATOMIC_UNITS, 0, RoundingMode.CEILING)
            .toBigIntegerExact()
        return KrwEstimateDto(
            amountWon = amountWon.toString(),
            rateWonPerUsdc = rate.stripTrailingZeros().toPlainString(),
            rateAsOf = rateAsOf,
            stale = stale,
        )
    }

    private fun freshRate(now: Instant): CachedRate? {
        val current = cachedRate
        if (current != null && now.isBefore(current.asOf.plus(properties.bithumbCacheTtl))) {
            return current
        }
        return runCatching {
            CachedRate(value = bithumbClient.currentUsdcKrwRate(), asOf = now)
                .also { fetched -> cachedRate = fetched }
        }.getOrNull()
    }

    private fun staleRate(now: Instant): CachedRate? {
        val current = cachedRate ?: return null
        return current.takeIf { rate -> now.isBefore(rate.asOf.plus(properties.bithumbStaleTtl)) }
    }

    private data class CachedRate(
        val value: BigDecimal,
        val asOf: Instant,
    )
}
