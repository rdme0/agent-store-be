package com.agentstore.execution

import com.agentstore.execution.dto.request.RuntimeDependencyInvocationRequest
import com.agentstore.execution.dto.response.ExecutionEventResponse
import com.agentstore.execution.event.ExecutionEventService
import com.agentstore.execution.runner.ExecutionRunner
import com.agentstore.execution.service.RuntimeCallbackService
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.dependency.resolver.CostResolver
import com.agentstore.dependency.resolver.DependencyResolver
import com.agentstore.payment.client.PaymentClient
import com.agentstore.payment.dto.internal.PaymentInvocationRequestDto
import com.agentstore.payment.dto.internal.PaymentInvocationResultDto
import com.agentstore.support.DependencyRuntimeFixture
import com.agentstore.support.PostgresIntegrationTestSupport
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary

@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
@Import(PostgresRuntimeE2eIntegrationTest.TestPaymentClientConfiguration::class)
class PostgresRuntimeE2eIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var runner: ExecutionRunner

    @Autowired
    private lateinit var events: ExecutionEventService

    @Autowired
    private lateinit var paymentClient: CallbackPaymentClient

    @Autowired
    private lateinit var dependencyResolver: DependencyResolver

    @Autowired
    private lateinit var costResolver: CostResolver

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `test payment client invokes declared dependency then completes with persisted SSE and revenue`() {
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
        assertThat(
            events.replay(
                executionId = fixture.root.executionId,
                afterSequence = 0,
            ).map { event -> event.type },
        ).contains(
            "DEPENDENCY_STEP_COMPLETED",
            "EXECUTION_COMPLETED"
        )
    }

    @Test
    fun `function contract reference selects one provider and settles distinct developer revenues`() {
        val registry = runtimeFixture.createFunctionContractMarketplaceRegistry()
        val graph = dependencyResolver.resolve(
            rootVersionId = registry.rootVersionId,
            allowUnresolvedRequired = false,
            allowPriceExceeded = false,
        )
        val cost = costResolver.resolve(root = graph.root)
        val functionContractEdge = graph.root.dependencies.single { edge -> edge.selection != null }
        val dependencyIds = graph.root.dependencies.map { edge -> edge.dependency.id }

        assertThat(dependencyIds).isEqualTo(dependencyIds.sortedBy(UUID::toString))
        assertThat(functionContractEdge.resolved?.version?.id).isEqualTo(registry.selectedProviderVersionId)
        assertThat(functionContractEdge.selection?.candidates?.map { candidate -> candidate.versionId })
            .containsExactly(registry.selectedProviderVersionId, registry.excludedProviderVersionId)
        assertThat(graph.root.dependencies.mapNotNull { edge -> edge.resolved?.version?.endpoint }.toSet())
            .hasSize(2)
        assertThat(registry.payTos).hasSize(4)
        val frozenSnapshot = objectMapper.writeValueAsString(graph.root.snapshot())
        val newlyPublishedVersionId = runtimeFixture.publishMorePreferredProvider(
            agentId = registry.excludedProviderAgentId,
            functionContractId = registry.functionContractId,
        )
        val currentGraph = dependencyResolver.resolve(
            rootVersionId = registry.rootVersionId,
            allowUnresolvedRequired = false,
            allowPriceExceeded = false,
        )
        assertThat(currentGraph.root.dependencies.single { edge -> edge.selection != null }.resolved?.version?.id)
            .isEqualTo(newlyPublishedVersionId)

        val root = runtimeFixture.createExecutionFromSnapshot(
            rootVersionId = registry.rootVersionId,
            rootCode = registry.rootCode,
            maxBudget = cost.maxCostAtomic,
            snapshot = frozenSnapshot,
        )
        val scenario = DependencyRuntimeFixture(
            root = root,
            rootCode = registry.rootCode,
            childCode = functionContractEdge.resolved?.version?.agentCode ?: error("Selected provider code is missing"),
            childVersionId = registry.selectedProviderVersionId,
        )
        paymentClient.arm(fixture = scenario)

        runner.start(root.executionId)
        awaitTerminalEvents(executionId = root.executionId)

        val execution = jdbcTemplate.queryForMap(
            "select status, actual_cost_atomic from executions where id = ?",
            root.executionId,
        )
        assertThat(execution["status"].toString()).isEqualTo("COMPLETED")
        assertThat((execution["actual_cost_atomic"] as Number).toLong()).isEqualTo(2_900)

        val revenues = jdbcTemplate.queryForList(
            """
            select developer_id, amount_atomic
            from revenue_entries
            where execution_step_id in (select id from execution_steps where execution_id = ?)
            """.trimIndent(),
            root.executionId,
        ).associate { row ->
            UUID.fromString(row["developer_id"].toString()) to (row["amount_atomic"] as Number).toLong()
        }
        assertThat(revenues).containsEntry(registry.rootDeveloperId, 1_000)
        assertThat(revenues).containsEntry(registry.selectedProviderDeveloperId, 900)
        assertThat(revenues).containsEntry(registry.directProviderDeveloperId, 1_000)
        assertThat(revenues).doesNotContainKey(registry.excludedProviderDeveloperId)
        assertThat(revenues).hasSize(3)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from execution_steps where execution_id = ? and agent_version_id = ?",
                Int::class.java,
                root.executionId,
                newlyPublishedVersionId,
            ),
        ).isZero()
    }

    @Test
    fun `root request forwards question and input to every runtime dependency`() {
        val fixture = runtimeFixture.createRootWithDependency(dependencyCount = 3)
        jdbcTemplate.update(
            "update executions set question = ?, input = ?::jsonb where id = ?",
            "삼성전자 투자 분석해줘",
            "{\"ticker\":\"005930\"}",
            fixture.root.executionId,
        )
        paymentClient.arm(fixture)

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        val rootBody = paymentClient.rootBody()
            ?: error("root payment invocation body was not captured")
        assertThat(rootBody.path("input").path("question").asText())
            .isEqualTo("삼성전자 투자 분석해줘")
        assertThat(rootBody.path("input").path("input").path("ticker").asText())
            .isEqualTo("005930")
        val dependencies = rootBody.path("runtime").path("dependencies")
        assertThat(dependencies).hasSize(3)
        dependencies.forEach { dependency ->
            assertThat(dependency.path("input")).isEqualTo(rootBody.path("input"))
        }
        assertThat(paymentClient.callbackInputs()).containsExactlyElementsOf(
            List(size = 3) { rootBody.path("input") },
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
    fun `Markdown root stores the agent output string instead of the HTTP envelope`() {
        val fixture = runtimeFixture.createRootWithDependency(rootResponseFormat = "MARKDOWN")
        paymentClient.arm(fixture)
        paymentClient.returnRootMarkdown("# 투자 분석\n\n## 요약\n신중히 검토하세요.")

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        val execution = jdbcTemplate.queryForMap(
            "select status from executions where id = ?",
            fixture.root.executionId,
        )
        assertThat(execution["status"].toString()).isEqualTo("COMPLETED")
        assertThat(
            jdbcTemplate.queryForObject(
                "select output::text from execution_steps where id = ?",
                String::class.java,
                fixture.root.rootStepId,
            ),
        ).isEqualTo("\"# 투자 분석\\n\\n## 요약\\n신중히 검토하세요.\"")
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

    @Test
    fun `root function contract output schema mismatch fails after settlement without losing payment evidence`() {
        val fixture = runtimeFixture.createRootWithDependency()
        runtimeFixture.attachFunctionContract(
            quoteId = fixture.root.quoteId,
            versionId = fixture.root.agentVersionId,
            outputSchema = """{"type":"object","required":["capabilityProof"]}""",
        )
        paymentClient.arm(fixture)

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        val execution = jdbcTemplate.queryForMap(
            "select status, failure_code from executions where id = ?",
            fixture.root.executionId,
        )
        assertThat(execution["status"].toString()).isEqualTo("FAILED")
        assertThat(execution["failure_code"]).isEqualTo("AGENT_OUTPUT_SCHEMA_INVALID")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_attempts where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `dependency function contract input schema mismatch prevents child payment`() {
        val fixture = runtimeFixture.createRootWithDependency()
        runtimeFixture.attachFunctionContract(
            quoteId = fixture.root.quoteId,
            versionId = fixture.childVersionId,
            inputSchema = """{"type":"object","required":["missingContext"]}""",
        )
        paymentClient.arm(fixture)

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        val execution = jdbcTemplate.queryForMap(
            "select status, failure_code from executions where id = ?",
            fixture.root.executionId,
        )
        assertThat(execution["status"].toString()).isEqualTo("FAILED")
        assertThat(execution["failure_code"]).isEqualTo("AGENT_INPUT_SCHEMA_INVALID")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_attempts where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `dependency function contract output schema mismatch preserves child settlement and stops fallback`() {
        val fixture = runtimeFixture.createRootWithDependency()
        runtimeFixture.attachFunctionContract(
            quoteId = fixture.root.quoteId,
            versionId = fixture.childVersionId,
            outputSchema = """{"type":"object","required":["capabilityProof"]}""",
        )
        paymentClient.arm(fixture)

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        val execution = jdbcTemplate.queryForMap(
            "select status, failure_code from executions where id = ?",
            fixture.root.executionId,
        )
        assertThat(execution["status"].toString()).isEqualTo("FAILED")
        assertThat(execution["failure_code"]).isEqualTo("AGENT_OUTPUT_SCHEMA_INVALID")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from payment_attempts where execution_step_id in (select id from execution_steps where execution_id = ?)",
                Int::class.java,
                fixture.root.executionId,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `text dependency stores the output value instead of the HTTP envelope`() {
        val fixture = runtimeFixture.createRootWithDependency(childResponseFormat = "TEXT")
        paymentClient.arm(fixture)
        paymentClient.returnChildText("재무 자료를 확인했습니다.")

        runner.start(fixture.root.executionId)
        awaitTerminalEvents(fixture.root.executionId)

        assertThat(
            jdbcTemplate.queryForObject(
                "select status from executions where id = ?",
                String::class.java,
                fixture.root.executionId,
            ),
        ).isEqualTo("COMPLETED")
        assertThat(
            jdbcTemplate.queryForObject(
                "select output::text from execution_steps where execution_id = ? and agent_version_id = ?",
                String::class.java,
                fixture.root.executionId,
                fixture.childVersionId,
            ),
        ).isEqualTo("\"재무 자료를 확인했습니다.\"")
    }

    private fun awaitTerminalEvents(executionId: UUID): List<ExecutionEventResponse> {
        repeat(50) {
            val replay = events.replay(executionId = executionId, afterSequence = 0)
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
                    executionId = executionId,
                    afterSequence = 0,
                )
            }"
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestPaymentClientConfiguration {
        @Bean
        @Primary
        fun callbackPaymentClient(
            tokenService: InvocationTokenService,
            callbackService: ObjectProvider<RuntimeCallbackService>,
            objectMapper: ObjectMapper,
        ): CallbackPaymentClient {
            return CallbackPaymentClient(
                tokenService = tokenService,
                callbackService = callbackService,
                objectMapper = objectMapper,
            )
        }
    }

    class CallbackPaymentClient(
        private val tokenService: InvocationTokenService,
        private val callbackService: ObjectProvider<RuntimeCallbackService>,
        private val objectMapper: ObjectMapper,
    ) : PaymentClient {

        @Volatile
        private var fixture: DependencyRuntimeFixture? = null

        @Volatile
        private var callbackException: Throwable? = null

        @Volatile
        private var lastRootBody: JsonNode? = null

        @Volatile
        private var rootOutput: JsonNode? = null

        @Volatile
        private var childOutput: JsonNode? = null

        private val callbackInputs = mutableListOf<JsonNode>()

        private val invocationCount = AtomicInteger()

        fun arm(fixture: DependencyRuntimeFixture) {
            this.fixture = fixture
            callbackException = null
            lastRootBody = null
            rootOutput = null
            childOutput = null
            callbackInputs.clear()
            invocationCount.set(0)
        }

        fun invocations(): Int {
            return invocationCount.get()
        }

        fun callbackFailure(): String? {
            return generateSequence(callbackException) { it.cause }
                .joinToString("\ncaused by: ") { it.stackTraceToString() }
        }

        fun rootBody(): JsonNode? {
            return lastRootBody
        }

        fun returnRootMarkdown(markdown: String) {
            rootOutput = objectMapper.createObjectNode().put("output", markdown)
        }

        fun returnChildText(text: String) {
            childOutput = objectMapper.createObjectNode().put("output", text)
        }

        fun callbackInputs(): List<JsonNode> {
            return callbackInputs.toList()
        }

        override fun invoke(request: PaymentInvocationRequestDto): PaymentInvocationResultDto {
            invocationCount.incrementAndGet()
            val scenario = fixture ?: error("callback payment client is not armed")
            val claims = tokenService.verify(request.invocationToken)
            if (claims.agentVersionId == scenario.root.agentVersionId) {
                val rootBody: JsonNode = objectMapper.valueToTree(request.body)
                lastRootBody = rootBody
                try {
                    rootBody.path("runtime").path("dependencies").forEachIndexed { index, dependency ->
                        val input = dependency.path("input")
                        callbackInputs.add(input)
                        callbackService.getObject().invoke(
                            executionId = claims.executionId,
                            request = RuntimeDependencyInvocationRequest(
                                agentVersionId = UUID.fromString(dependency.path("agentVersionId").asText()),
                                callPath = dependency.path("callPath").map { node -> node.asText() },
                                input = input,
                            ),
                            authorization = "Bearer ${request.invocationToken}",
                            idempotencyKey = "fixture-root-dependency-$index",
                        )
                    }
                } catch (exception: Throwable) {
                    callbackException = exception
                    throw exception
                }
                return PaymentInvocationResultDto(
                    output = rootOutput ?: objectMapper.readTree("{\"agent\":\"root\",\"status\":\"completed\"}"),
                    transactionHash = transactionHash(request = request),
                    paymentIdentifier = request.paymentAttemptId,
                )
            }
            return PaymentInvocationResultDto(
                output = childOutput ?: objectMapper.readTree("{\"agent\":\"child\",\"status\":\"completed\"}"),
                transactionHash = transactionHash(request = request),
                paymentIdentifier = request.paymentAttemptId,
            )
        }

        private fun transactionHash(request: PaymentInvocationRequestDto): String {
            return "0x${request.paymentAttemptId.replace("-", "").padEnd(64, '0')}"
        }
    }
}
