package com.agentstore.execution

import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.repository.ExecutionRepository
import com.agentstore.support.PostgresIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
class PostgresBudgetConcurrencyIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var budgetGuard: BudgetGuard
    @Autowired
    private lateinit var executionRepository: ExecutionRepository

    @Test
    fun `concurrent reservation never exceeds execution maximum budget`() {
        val fixture = runtimeFixture.create(BigInteger.TEN)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) {
                Callable {
                    runCatching { budgetGuard.reserve(fixture.executionId, BigInteger.valueOf(6)) }.isSuccess
                }
            }).map { it.get() }

            assertThat(results.count { it }).isEqualTo(1)
            val execution = executionRepository.findById(fixture.executionId).orElseThrow()
            assertThat(execution.reservedCostAtomic).isEqualTo(BigInteger.valueOf(6))
            assertThat(execution.actualCostAtomic.add(execution.reservedCostAtomic)).isLessThanOrEqualTo(BigInteger.TEN)
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
