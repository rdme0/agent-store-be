package com.agentstore.execution

import com.agentstore.execution.controller.ExecutionController
import com.agentstore.execution.event.ExecutionEventBroker
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.support.PostgresIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
class PostgresSseIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var events: ExecutionEventService
    @Autowired
    private lateinit var broker: ExecutionEventBroker
    @Autowired
    private lateinit var controller: ExecutionController

    @Test
    fun `stored replay and live publication do not duplicate a sequence`() {
        val fixture = runtimeFixture.create()
        val first = events.append(fixture.executionId, "EXECUTION_RUNNING", mapOf("stepId" to fixture.rootStepId))
        val sent = AtomicInteger()
        val emitter = controller.events(fixture.executionId, first.sequence.toString())
        attachHandler(emitter, sent, AtomicInteger())
        val second = events.append(fixture.executionId, "STEP_RUNNING", mapOf("stepId" to fixture.rootStepId))

        assertThat(second.sequence).isEqualTo(first.sequence + 1)
        assertThat(sent.get()).isEqualTo(1)
    }

    @Test
    fun `terminal live event completes the subscription`() {
        val fixture = runtimeFixture.create()
        val completed = AtomicInteger()
        val emitter = broker.subscribe(fixture.executionId, 0) { emptyList() }
        attachHandler(emitter, AtomicInteger(), completed)

        events.append(fixture.executionId, "EXECUTION_COMPLETED", mapOf("stepId" to fixture.rootStepId))

        assertThat(completed.get()).isEqualTo(1)
    }

    @Test
    fun `controller replays Last Event ID then delivers one live terminal event`() {
        val fixture = runtimeFixture.create()
        val first = events.append(fixture.executionId, "EXECUTION_RUNNING", mapOf("stepId" to fixture.rootStepId))
        val replayed = events.append(fixture.executionId, "PAYMENT_REQUIRED", mapOf("stepId" to fixture.rootStepId))
        val sent = AtomicInteger()
        val completed = AtomicInteger()
        val emitter = controller.events(fixture.executionId, first.sequence.toString())
        attachHandler(emitter, sent, completed)

        events.append(fixture.executionId, "EXECUTION_COMPLETED", mapOf("stepId" to fixture.rootStepId))

        assertThat(replayed.sequence).isEqualTo(first.sequence + 1)
        assertThat(sent.get()).isEqualTo(2)
        assertThat(completed.get()).isEqualTo(1)
    }

    private fun attachHandler(emitter: Any, sent: AtomicInteger, completed: AtomicInteger) {
        val handlerType =
            Class.forName("org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter\$Handler")
        val handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType)) { _, method, _ ->
            when (method.name) {
                "send" -> sent.incrementAndGet()
                "complete" -> completed.incrementAndGet()
            }
            null
        }
        val initialize =
            emitter.javaClass.superclass.declaredMethods.first { it.name == "initialize" && it.parameterCount == 1 }
        initialize.isAccessible = true
        initialize.invoke(emitter, handler)
    }
}
