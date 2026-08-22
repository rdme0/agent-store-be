package com.agentstore.execution

import com.agentstore.execution.guard.RuntimeCallbackAdmissionService
import com.agentstore.support.PostgresIntegrationTestSupport
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
class PostgresCallbackIdempotencyIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var admission: RuntimeCallbackAdmissionService

    @Test
    fun `duplicate callback idempotency key creates one child step`() {
        val fixture = runtimeFixture.create(executionStatus = "RUNNING")
        val key = "callback-idempotency"
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll(List(2) {
                Callable {
                    admission.admit(
                        fixture.executionId,
                        fixture.rootStepId,
                        fixture.agentVersionId,
                        listOf("fixture", "child"),
                        key
                    ).id
                }
            }).map { it.get() }

            assertThat(outcomes.distinct()).hasSize(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from execution_steps where parent_step_id = ? and idempotency_key = ?",
                    Int::class.java,
                    fixture.rootStepId,
                    key
                )
            ).isEqualTo(1)
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
