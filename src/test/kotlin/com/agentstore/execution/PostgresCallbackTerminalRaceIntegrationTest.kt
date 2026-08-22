package com.agentstore.execution

import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.guard.RuntimeCallbackAdmissionService
import com.agentstore.execution.orchestrator.ExecutionPaymentSettlementService
import com.agentstore.execution.service.ExecutionRunService
import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.support.PostgresIntegrationTestSupport
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
class PostgresCallbackTerminalRaceIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var admission: RuntimeCallbackAdmissionService

    @Autowired
    private lateinit var executionRunService: ExecutionRunService

    @Autowired
    private lateinit var settlement: ExecutionPaymentSettlementService

    @Autowired
    private lateinit var paymentService: PaymentService

    @Autowired
    private lateinit var budgetGuard: BudgetGuard

    @Test
    fun `callback admission and terminalization leave no active child under terminal execution`() {
        val fixture = runtimeFixture.create(executionStatus = "RUNNING")
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val callback = pool.submit(Callable {
                barrier.await()
                runCatching {
                    admission.admit(
                        fixture.executionId,
                        fixture.rootStepId,
                        fixture.agentVersionId,
                        listOf("fixture", "child"),
                        "race-${UUID.randomUUID()}"
                    )
                }.isSuccess
            })
            val terminal = pool.submit(Callable {
                barrier.await()
                executionRunService.fail(fixture.executionId, "SERVER_RESTART")
            })
            callback.get(10, TimeUnit.SECONDS)
            terminal.get(10, TimeUnit.SECONDS)

            assertThat(
                jdbcTemplate.queryForObject(
                    "select status::text from executions where id = ?",
                    String::class.java,
                    fixture.executionId
                )
            ).isEqualTo("FAILED")
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from execution_steps where execution_id = ? and status in ('CREATED'::\"ExecutionStepStatus\", 'PAYMENT_REQUIRED'::\"ExecutionStepStatus\", 'PAYMENT_SETTLED'::\"ExecutionStepStatus\", 'RUNNING'::\"ExecutionStepStatus\")",
                    Int::class.java,
                    fixture.executionId
                )
            ).isZero()
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `terminalization racing an external response keeps a journal and never releases an observed payment`() {
        val fixture = runtimeFixture.create(maxBudget = BigInteger.ONE, executionStatus = "RUNNING")
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.SIMULATED
        )
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val external = pool.submit(Callable {
                barrier.await()
                settlement.settleIfActive(
                    fixture.executionId,
                    fixture.rootStepId,
                    attemptId,
                    BigInteger.ONE,
                    "0x${UUID.randomUUID().toString().replace("-", "")}",
                    attemptId.toString(),
                    RevenueType.DIRECT,
                    PaymentMode.SIMULATED
                )
            })
            val terminal = pool.submit(Callable {
                barrier.await()
                executionRunService.fail(fixture.executionId, "SERVER_RESTART")
            })
            external.get(10, TimeUnit.SECONDS)
            terminal.get(10, TimeUnit.SECONDS)

            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from payment_settlement_journals where payment_attempt_id = ?",
                    Int::class.java,
                    attemptId
                )
            ).isEqualTo(1)
            val costs = jdbcTemplate.queryForMap(
                "select reserved_cost_atomic, actual_cost_atomic from executions where id = ?",
                fixture.executionId
            )
            assertThat((costs["reserved_cost_atomic"] as Number).toLong() + (costs["actual_cost_atomic"] as Number).toLong()).isEqualTo(
                1
            )
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
