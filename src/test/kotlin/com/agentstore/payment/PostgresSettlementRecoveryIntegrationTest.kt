package com.agentstore.payment

import com.agentstore.execution.guard.BudgetGuard
import com.agentstore.execution.orchestrator.ExecutionPaymentRecoveryOrchestrator
import com.agentstore.execution.orchestrator.ExecutionPaymentSettlementService
import com.agentstore.execution.service.ExecutionLifecycleService
import com.agentstore.execution.service.ExecutionRecoveryService
import com.agentstore.payment.client.PaymentReconciliationClient
import com.agentstore.payment.dto.internal.BridgeReconciliationResult
import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.BridgeReconciliationStatus
import com.agentstore.payment.model.vo.PaymentMode
import com.agentstore.payment.repository.PaymentSettlementJournalRepository
import com.agentstore.payment.service.PaymentService
import com.agentstore.revenue.model.vo.RevenueType
import com.agentstore.revenue.repository.RevenueEntryRepository
import com.agentstore.revenue.service.RevenueSettlementService
import com.agentstore.support.PostgresIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.math.BigInteger
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
@Import(PostgresSettlementRecoveryIntegrationTest.ReconciliationTestConfiguration::class)
class PostgresSettlementRecoveryIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var paymentService: PaymentService
    @Autowired
    private lateinit var budgetGuard: BudgetGuard
    @Autowired
    private lateinit var recovery: ExecutionPaymentRecoveryOrchestrator
    @Autowired
    private lateinit var journals: PaymentSettlementJournalRepository
    @Autowired
    private lateinit var revenues: RevenueEntryRepository
    @Autowired
    private lateinit var revenueSettlement: RevenueSettlementService
    @Autowired
    private lateinit var reconciliationClient: TestReconciliationClient
    @Autowired
    private lateinit var executionRecovery: ExecutionRecoveryService
    @Autowired
    private lateinit var lifecycleService: ExecutionLifecycleService
    @Autowired
    private lateinit var settlementService: ExecutionPaymentSettlementService

    @Test
    fun `duplicate settlement keeps one journal and one revenue entry`() {
        val fixture = runtimeFixture.create()
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.SIMULATED
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        val hash = "0x${UUID.randomUUID().toString().replace("-", "")}"

        paymentService.settle(attemptId, hash, "payment-$attemptId")
        paymentService.settle(attemptId, hash, "payment-$attemptId")
        val revenue = revenueSettlement.record(paymentService.find(attemptId), RevenueType.DIRECT)
        fixtureCleaner.trackRevenueEntry(revenue.id)
        revenueSettlement.record(paymentService.find(attemptId), RevenueType.DIRECT)

        assertThat(journals.findByPaymentAttemptId(attemptId)).isNotNull
        assertThat(revenues.findByPaymentAttemptId(attemptId)).isNotNull
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_settlement_journals where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `restart reconciliation settles reserved cost and records revenue once`() {
        val fixture = runtimeFixture.create()
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.SIMULATED
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.settle(attemptId, "0x${UUID.randomUUID().toString().replace("-", "")}", "payment-$attemptId")

        recovery.reconcileSettledPayments()
        recovery.reconcileSettledPayments()

        revenues.findByPaymentAttemptId(attemptId)?.let { fixtureCleaner.trackRevenueEntry(it.id) }

        assertThat(
            jdbcTemplate.queryForObject(
                "select reserved_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ZERO)
        assertThat(
            jdbcTemplate.queryForObject(
                "select actual_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `normal settlement is marked projected and is ignored after restart`() {
        val fixture = runtimeFixture.create(executionStatus = "RUNNING")
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.SIMULATED
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)

        assertThat(
            settlementService.settleIfActive(
                fixture.executionId, fixture.rootStepId, attemptId, BigInteger.ONE,
                "0x${UUID.randomUUID().toString().replace("-", "")}", "payment-$attemptId",
                RevenueType.DIRECT, PaymentMode.SIMULATED,
            )
        ).isTrue()
        assertThat(paymentService.find(attemptId).projectedAt).isNotNull()

        assertThat(recovery.reconcileSettledPayments()).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "select actual_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `journal-backed post-settlement recovery is idempotent across a second restart`() {
        val fixture = runtimeFixture.create()
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.SIMULATED
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.settle(attemptId, "0x${UUID.randomUUID().toString().replace("-", "")}", "payment-$attemptId")
        paymentService.markSettlementRecoveryRequired(attemptId, "FAILED_AFTER_PAYMENT")

        recovery.reconcileSettledPayments()
        recovery.reconcileSettledPayments()
        revenues.findByPaymentAttemptId(attemptId)?.let { fixtureCleaner.trackRevenueEntry(it.id) }

        assertThat(paymentService.find(attemptId).failureCode).isNull()
        assertThat(
            jdbcTemplate.queryForObject(
                "select actual_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `unknown x402 payment settles exactly once after bridge reconciliation`() {
        val fixture = runtimeFixture.create()
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.X402
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.markReconciliationRequired(attemptId, "PAYMENT_RECONCILIATION_REQUIRED")
        reconciliationClient.settled(attemptId, "0x${UUID.randomUUID().toString().replace("-", "")}")

        recovery.reconcileSettledPayments()
        recovery.reconcileSettledPayments()

        revenues.findByPaymentAttemptId(attemptId)?.let { fixtureCleaner.trackRevenueEntry(it.id) }
        assertThat(paymentService.find(attemptId).status.name).isEqualTo("SETTLED")
        assertThat(
            jdbcTemplate.queryForObject(
                "select reserved_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ZERO)
        assertThat(
            jdbcTemplate.queryForObject(
                "select actual_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `unknown x402 reconciliation retains reservation and creates neither revenue nor journal`() {
        val fixture = runtimeFixture.create()
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.X402
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.markReconciliationRequired(attemptId, "PAYMENT_RECONCILIATION_REQUIRED")

        recovery.reconcileSettledPayments()

        assertThat(paymentService.find(attemptId).status.name).isEqualTo("RECONCILIATION_REQUIRED")
        assertThat(
            jdbcTemplate.queryForObject(
                "select reserved_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(
            jdbcTemplate.queryForObject(
                "select actual_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ZERO)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isZero()
        assertThat(journals.findByPaymentAttemptId(attemptId)).isNull()
    }

    @Test
    fun `dependency recovery records dependency revenue exactly once`() {
        val fixture = runtimeFixture.createRootWithDependency()
        val childStepId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into execution_steps (id, execution_id, parent_step_id, agent_version_id, status, call_path, cost_atomic, created_at, updated_at) values (?, ?, ?, ?, 'PAYMENT_REQUIRED'::\"ExecutionStepStatus\", '[\"root\", \"child\"]'::jsonb, 0, current_timestamp, current_timestamp)",
            childStepId, fixture.root.executionId, fixture.root.rootStepId, fixture.childVersionId,
        )
        fixtureCleaner.trackStep(childStepId)
        budgetGuard.reserve(fixture.root.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            childStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.SIMULATED
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.settle(attemptId, "0x${UUID.randomUUID().toString().replace("-", "")}", "payment-$attemptId")

        recovery.reconcileSettledPayments()

        assertThat(revenues.findByPaymentAttemptId(attemptId)?.type).isEqualTo(RevenueType.DEPENDENCY)
        assertThat(paymentService.find(attemptId).projectedAt).isNotNull()
    }

    @Test
    fun `concurrent recovery projects one settled attempt once`() {
        val fixture = runtimeFixture.create()
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.SIMULATED
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.settle(attemptId, "0x${UUID.randomUUID().toString().replace("-", "")}", "payment-$attemptId")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit(Callable { recovery.reconcileSettledPayments() }) }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select actual_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from execution_events where execution_id = ? and type = 'PAYMENT_SETTLED'",
                Int::class.java,
                fixture.executionId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `startup recovery leaves unknown payment reserved and later reconciles settled without repayment`() {
        val fixture = runtimeFixture.create(maxBudget = BigInteger.ONE, executionStatus = "RUNNING")
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.X402
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.markReconciliationRequired(attemptId, "PAYMENT_RECONCILIATION_REQUIRED")

        executionRecovery.failActiveExecutions()
        recovery.reconcileSettledPayments()

        assertThat(
            jdbcTemplate.queryForObject(
                "select reserved_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(paymentService.find(attemptId).status.name).isEqualTo("RECONCILIATION_REQUIRED")
        reconciliationClient.settled(attemptId, "0x${UUID.randomUUID().toString().replace("-", "")}")
        recovery.reconcileSettledPayments()

        assertThat(
            jdbcTemplate.queryForObject(
                "select actual_cost_atomic from executions where id = ?",
                BigInteger::class.java,
                fixture.executionId
            )
        ).isEqualTo(BigInteger.ONE)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
    }

    @Test
    fun `reconciliation projection racing terminalization journals and accounts once`() {
        val fixture = runtimeFixture.create(maxBudget = BigInteger.ONE, executionStatus = "RUNNING")
        budgetGuard.reserve(fixture.executionId, BigInteger.ONE)
        val attemptId = paymentService.require(
            fixture.rootStepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0x0000000000000000000000000000000000000001",
            PaymentMode.X402
        )
        fixtureCleaner.trackPaymentAttempt(attemptId)
        paymentService.markReconciliationRequired(attemptId, "PAYMENT_RECONCILIATION_REQUIRED")
        reconciliationClient.settled(attemptId, "0x${UUID.randomUUID().toString().replace("-", "")}")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val projection = pool.submit(Callable { start.await(); recovery.reconcileSettledPayments() })
            val terminal = pool.submit(Callable {
                start.await(); lifecycleService.fail(
                fixture.executionId,
                fixture.rootStepId,
                "SERVER_RESTART"
            )
            })
            start.countDown()
            projection.get(10, TimeUnit.SECONDS)
            terminal.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_settlement_journals where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where payment_attempt_id = ?",
                Int::class.java,
                attemptId
            )
        ).isEqualTo(1)
        val reserved = jdbcTemplate.queryForObject(
            "select reserved_cost_atomic from executions where id = ?",
            BigInteger::class.java,
            fixture.executionId
        )
        val actual = jdbcTemplate.queryForObject(
            "select actual_cost_atomic from executions where id = ?",
            BigInteger::class.java,
            fixture.executionId
        )
        assertThat(reserved!! + actual!!).isEqualTo(BigInteger.ONE)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ReconciliationTestConfiguration {
        @Bean
        @Primary
        fun testReconciliationClient(): TestReconciliationClient {
            return TestReconciliationClient()
        }
    }

    class TestReconciliationClient : PaymentReconciliationClient {
        private val settled = mutableMapOf<UUID, String>()
        fun settled(attemptId: UUID, transactionHash: String) {
            settled[attemptId] = transactionHash
        }

        override fun reconcile(attempt: PaymentAttempt): BridgeReconciliationResult {
            return settled[attempt.id]?.let {
                BridgeReconciliationResult(BridgeReconciliationStatus.SETTLED, it, "payment-${attempt.id}")
            } ?: BridgeReconciliationResult(BridgeReconciliationStatus.UNKNOWN)
        }
    }
}
