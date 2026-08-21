package com.agentstore.execution

import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.runner.ExecutionRunner
import com.agentstore.execution.service.RuntimeCallbackService
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.dto.internal.PaymentInvocationRequest
import com.agentstore.payment.dto.internal.PaymentInvocationResult
import com.agentstore.support.DependencyRuntimeFixture
import com.agentstore.support.PostgresIntegrationTestSupport
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
@Import(PostgresSimulatedRuntimeE2eIntegrationTest.PaymentClientConfiguration::class)
class PostgresSimulatedRuntimeE2eIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var runner: ExecutionRunner
    @Autowired
    private lateinit var events: ExecutionEventService
    @Autowired
    private lateinit var paymentClient: CallbackPaymentClient

    @Test
    fun `simulated root invokes declared dependency then completes with persisted SSE and revenue`() {
        val fixture = runtimeFixture.createRootWithDependency()
        paymentClient.arm(fixture)

        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { runner.start(fixture.root.executionId) }.get(10, TimeUnit.SECONDS)
            val terminalEvents = awaitTerminalEvents(fixture.root.executionId)
            val terminalState = jdbcTemplate.queryForMap(
                "select status, failure_code, reserved_cost_atomic, actual_cost_atomic from executions where id = ?",
                fixture.root.executionId,
            )
            assertThat(terminalEvents.last().type)
                .withFailMessage(
                    "execution: %s; payment client calls: %s; callback failure: %s; terminal events: %s",
                    terminalState,
                    paymentClient.invocations(),
                    paymentClient.callbackFailure(),
                    terminalEvents
                )
                .isEqualTo("EXECUTION_COMPLETED")
        } finally {
            executor.shutdownNow()
        }

        val execution = jdbcTemplate.queryForMap(
            "select status, reserved_cost_atomic, actual_cost_atomic from executions where id = ?",
            fixture.root.executionId
        )
        assertThat(execution["status"].toString()).isEqualTo("COMPLETED")
        assertThat((execution["reserved_cost_atomic"] as Number).toLong()).isZero()
        assertThat((execution["actual_cost_atomic"] as Number).toLong()).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from execution_steps where execution_id = ? and status = 'COMPLETED'::\"ExecutionStepStatus\"",
                Int::class.java,
                fixture.root.executionId
            )
        ).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_attempts where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId
            )
        ).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId
            )
        ).isEqualTo(2)
        assertThat(events.replay(fixture.root.executionId, 0).map { it.type }).contains(
            "DEPENDENCY_STEP_COMPLETED",
            "EXECUTION_COMPLETED"
        )
    }

    @Test
    fun `root output format mismatch fails execution and retains settled payment evidence`() {
        val fixture = runtimeFixture.createRootWithDependency(rootResponseFormat = "TEXT")
        paymentClient.arm(fixture)

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        val execution = jdbcTemplate.queryForMap(
            "select status, failure_code from executions where id = ?",
            fixture.root.executionId
        )
        assertThat(execution["status"].toString()).isEqualTo("FAILED")
        assertThat(execution["failure_code"]).isEqualTo("AGENT_OUTPUT_FORMAT_INVALID")
        assertThat(
            jdbcTemplate.queryForObject(
                "select failure_code from execution_steps where id = ?",
                String::class.java,
                fixture.root.rootStepId
            )
        ).isEqualTo("AGENT_OUTPUT_FORMAT_INVALID")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_attempts where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId
            )
        ).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId
            )
        ).isEqualTo(2)
    }

    @Test
    fun `dependency output format mismatch fails dependency step and owning execution`() {
        val fixture = runtimeFixture.createRootWithDependency(childResponseFormat = "TEXT")
        paymentClient.arm(fixture)

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        val execution = jdbcTemplate.queryForMap(
            "select status, failure_code from executions where id = ?",
            fixture.root.executionId
        )
        assertThat(execution["status"].toString()).isEqualTo("FAILED")
        assertThat(execution["failure_code"]).isEqualTo("AGENT_OUTPUT_FORMAT_INVALID")
        val childStep = jdbcTemplate.queryForMap(
            "select status, failure_code from execution_steps where execution_id = ? and agent_version_id = ?",
            fixture.root.executionId,
            fixture.childVersionId
        )
        assertThat(childStep["status"].toString()).isEqualTo("FAILED")
        assertThat(childStep["failure_code"]).isEqualTo("AGENT_OUTPUT_FORMAT_INVALID")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_attempts where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId
            )
        ).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from revenue_entries where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId
            )
        ).isEqualTo(1)
    }

    private fun awaitTerminalEvents(executionId: UUID): List<com.agentstore.execution.dto.response.ExecutionEventResponse> {
        repeat(50) {
            val replay = events.replay(executionId, 0)
            if (replay.lastOrNull()?.type in setOf(
                    "EXECUTION_COMPLETED",
                    "EXECUTION_FAILED",
                    "EXECUTION_RECONCILIATION_REQUIRED"
                )
            ) {
                return replay
            }
            Thread.sleep(100)
        }
        val execution = jdbcTemplate.queryForMap(
            "select status, failure_code, reserved_cost_atomic, actual_cost_atomic from executions where id = ?",
            executionId
        )
        val steps = jdbcTemplate.queryForList(
            "select id, status, failure_code from execution_steps where execution_id = ? order by created_at",
            executionId
        )
        error(
            "Timed out waiting for terminal SSE event; execution=$execution steps=$steps events=${
                events.replay(
                    executionId,
                    0
                )
            }"
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class PaymentClientConfiguration {
        @Bean
        @Primary
        fun callbackPaymentClient(
            tokenService: InvocationTokenService,
            callbackService: ObjectProvider<RuntimeCallbackService>,
            objectMapper: ObjectMapper,
        ): CallbackPaymentClient = CallbackPaymentClient(tokenService, callbackService, objectMapper)
    }

    class CallbackPaymentClient(
        private val tokenService: InvocationTokenService,
        private val callbackService: ObjectProvider<RuntimeCallbackService>,
        private val objectMapper: ObjectMapper,
    ) : PaymentClient {
        override val mode = com.agentstore.payment.model.vo.PaymentMode.SIMULATED

        @Volatile
        private var fixture: DependencyRuntimeFixture? = null
        @Volatile
        private var callbackException: Throwable? = null
        private val invocationCount = java.util.concurrent.atomic.AtomicInteger()

        fun arm(fixture: DependencyRuntimeFixture) {
            this.fixture = fixture
            callbackException = null
            invocationCount.set(0)
        }

        fun invocations(): Int {
            return invocationCount.get()
        }

        fun callbackFailure(): String? {
            return generateSequence(callbackException) { it.cause }
                .joinToString("\ncaused by: ") { it.stackTraceToString() }
        }

        override fun invoke(request: PaymentInvocationRequest): PaymentInvocationResult {
            invocationCount.incrementAndGet()
            val scenario = fixture ?: error("callback payment client is not armed")
            val claims = tokenService.verify(request.invocationToken)
            if (claims.agentVersionId == scenario.root.agentVersionId) {
                try {
                    callbackService.getObject().invoke(
                        claims.executionId,
                        RuntimeDependencyInvocationRequest(
                            scenario.childVersionId,
                            listOf(scenario.rootSlug, scenario.childSlug),
                            objectMapper.readTree("{\"question\":\"fixture\"}")
                        ),
                        "Bearer ${request.invocationToken}",
                        "fixture-root-dependency",
                    )
                } catch (exception: Throwable) {
                    callbackException = exception
                    throw exception
                }
                return PaymentInvocationResult(
                    objectMapper.readTree("{\"agent\":\"root\",\"status\":\"completed\"}"),
                    "simulated-root-${request.paymentAttemptId}",
                    request.paymentAttemptId
                )
            }
            return PaymentInvocationResult(
                objectMapper.readTree("{\"agent\":\"child\",\"status\":\"completed\"}"),
                "simulated-child-${request.paymentAttemptId}",
                request.paymentAttemptId
            )
        }
    }
}
