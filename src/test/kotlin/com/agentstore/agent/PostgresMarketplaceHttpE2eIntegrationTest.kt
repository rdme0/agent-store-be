package com.agentstore.agent

import com.agentstore.AgentStoreApplication
import com.agentstore.agent.config.DevIdentityInitializer
import com.agentstore.agent.model.vo.AgentVersionReadinessStatus
import com.agentstore.agent.service.ProviderReadinessService
import com.agentstore.external.client.FacilitatorIncomingPaymentGateway
import com.agentstore.external.dto.internal.IncomingPaymentSettlementDto
import com.agentstore.external.dto.internal.IncomingPaymentVerificationDto
import com.agentstore.execution.token.InvocationTokenService
import com.agentstore.support.PostgresIntegrationTestSupport
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.agentstore.x402.codec.X402HeaderCodec
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "SPRING_EXCLUSIVE_MAINTENANCE", matches = "true")
@SpringBootTest(
    classes = [AgentStoreApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=\${INTEGRATION_DATASOURCE_URL}",
        "spring.datasource.username=postgres",
        "spring.datasource.password=\${INTEGRATION_DATASOURCE_PASSWORD}",
        "agent-store.service-name=agent-store-api",
        "agent-store.api-version=0.1.0",
        "agent-store.runtime-callback-base-url=http://127.0.0.1:8080",
        "agent-store.cors-origins=http://localhost:*",
        "agent-store.runtime-token-secret=integration-runtime-secret",
        "X402_PRIVATE_KEY=0x1111111111111111111111111111111111111111111111111111111111111111",
    ],
)
@Import(ExternalPaymentFixtureConfiguration::class)
@ActiveProfiles("postgres-integration", "test")
class PostgresMarketplaceHttpE2eIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var invocationTokenService: InvocationTokenService

    @Autowired
    private lateinit var facilitatorFixture: DeterministicFacilitatorFixture

    @Autowired
    private lateinit var readinessService: ProviderReadinessService

    @LocalServerPort
    private var port: Int = 0

    private val httpClient = HttpClient.newHttpClient()
    private lateinit var accessToken: String

    @BeforeEach
    fun startDemoAccess() {
        val response = sendJson(method = "POST", path = "/api/demo/access", body = "", includeAccess = false)
        assertThat(response.statusCode()).isEqualTo(200)
        accessToken = objectMapper.readTree(response.body()).path("result").path("accessToken").textValue()
    }

    @Test
    fun `health HTTP operation returns common response and trace header`() {
        val response = get(path = "/health")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent()
        val body = objectMapper.readTree(response.body())
        assertThat(body.path("isSuccess").booleanValue()).isTrue()
        assertThat(body.path("result").path("status").textValue()).isEqualTo("ok")
    }

    @Test
    fun `openapi documents demo bearer security and x402 CORS preflight allows payment signature`() {
        val openApi = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/openapi.json"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(openApi.statusCode()).isEqualTo(200)
        val document = objectMapper.readTree(openApi.body())
        assertThat(document.path("components").path("securitySchemes").path("demoBearer").path("scheme").textValue())
            .isEqualTo("bearer")
        assertThat(document.path("paths").path("/api/developer/me").path("get").path("security").toString())
            .contains("demoBearer")

        val preflight = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations"))
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,payment-signature,idempotency-key")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build()
        val preflightResponse = httpClient.send(preflight, HttpResponse.BodyHandlers.ofString())
        assertThat(preflightResponse.statusCode()).isEqualTo(200)
        assertThat(preflightResponse.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173")
        assertThat(preflightResponse.headers().firstValue("Access-Control-Allow-Headers").orElse(""))
            .containsIgnoringCase("payment-signature")
    }

    @Test
    fun `demo bearer is not accepted as runtime callback or external receipt authentication`() {
        val invocationId = UUID.randomUUID()
        val external = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations/$invocationId"))
                .header("Authorization", "Bearer $accessToken")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertCommonError(external, expectedStatus = 404)

        val callback = sendJson(
            method = "POST",
            path = "/api/runtime/executions/$invocationId/dependencies/invoke",
            body = "{}",
        )
        assertCommonError(callback, expectedStatus = 401)
    }

    @Test
    fun `runtime callback accepts only an invocation bearer and reaches the state machine`() {
        val fixture = runtimeFixture.createRootWithDependency()
        jdbcTemplate.update(
            "update executions set status = 'RUNNING'::\"ExecutionStatus\" where id = ?",
            fixture.root.executionId,
        )
        val token = invocationTokenService.issue(
            executionId = fixture.root.executionId,
            stepId = fixture.root.rootStepId,
            agentVersionId = fixture.root.agentVersionId,
            callPath = listOf(fixture.rootCode),
        )
        val callback = sendJsonWithAuthorization(
            method = "POST",
            path = "/api/runtime/executions/${fixture.root.executionId}/dependencies/invoke",
            body = "{\"agentVersionId\":\"${fixture.childVersionId}\",\"callPath\":[\"${fixture.rootCode}\",\"${fixture.childCode}\"],\"input\":{}}",
            authorization = "Bearer $token",
        )
        assertThat(callback.statusCode()).isNotEqualTo(401)
        val body = objectMapper.readTree(callback.body())
        assertThat(body.path("isSuccess").booleanValue()).isFalse()
        assertThat(body.path("errorCode").textValue()).isNotBlank()
    }

    @Test
    fun `demo bearer authenticates developer reads and missing bearer is rejected with common response`() {
        val anonymousRequest = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port/api/developer/me"))
            .GET()
            .build()
        val anonymousResponse = HttpClient.newHttpClient().send(anonymousRequest, HttpResponse.BodyHandlers.ofString())
        assertCommonError(anonymousResponse, expectedStatus = 401)

        val me = get(path = "/api/developer/me")
        assertThat(me.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(me.body()).path("result").path("id").textValue())
            .isEqualTo(DevIdentityInitializer.DEMO_DEVELOPER_ID.toString())
        assertThat(get(path = "/api/developer/agents").statusCode()).isEqualTo(200)
        assertThat(get(path = "/api/developer/revenue").statusCode()).isEqualTo(200)
    }

    @Test
    fun `forged and expired bearer tokens are rejected before developer access`() {
        val forged = accessToken.substringBeforeLast('.') + ".invalid"
        val forgedResponse = getWithToken(path = "/api/developer/me", token = forged)
        assertCommonError(forgedResponse, expectedStatus = 401)

        val expiredPayload = "demo-access.${DevIdentityInitializer.DEMO_DEVELOPER_ID}.0.1"
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(expiredPayload.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("demo-access:integration-runtime-secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encodedPayload.toByteArray(Charsets.UTF_8)))
        val expired = "$encodedPayload.$signature"
        val expiredResponse = getWithToken(path = "/api/developer/me", token = expired)
        assertCommonError(expiredResponse, expectedStatus = 401)
    }

    @Test
    fun `foreign version and revenue owner mutations are rejected with bearer principal`() {
        val foreignDeveloperId = UUID.randomUUID()
        val foreignUserId = fixtureCleaner.createStandaloneUser()
        val foreignAgentId = UUID.randomUUID()
        val foreignVersionId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into developers (id, user_id, display_name, created_at, updated_at) values (?, ?, ?, current_timestamp, current_timestamp)",
            foreignDeveloperId,
            foreignUserId,
            "Foreign owner",
        )
        fixtureCleaner.trackDeveloper(foreignDeveloperId)
        jdbcTemplate.update(
            "insert into agents (id, developer_id, code, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
            foreignAgentId,
            foreignDeveloperId,
            "foreign-${UUID.randomUUID()}",
            "Foreign agent",
            "Foreign ownership fixture",
        )
        fixtureCleaner.trackAgent(foreignAgentId)
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, '1.0.0', 'ACTIVE'::\"AgentVersionStatus\", 'http://127.0.0.1:8090/agents/foreign/invoke', 1, 'eip155:84532', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', '0x0000000000000000000000000000000000000001', 'JSON'::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            foreignVersionId,
            foreignAgentId,
        )
        fixtureCleaner.trackAgentVersion(foreignVersionId)

        val versionResponse = sendJson(
            method = "POST",
            path = "/api/agent-versions/$foreignVersionId/disable",
            body = "{}",
        )
        assertCommonError(versionResponse, expectedStatus = 403)

        val manifestResponse = sendJson(
            method = "PUT",
            path = "/api/agent-versions/$foreignVersionId/manifest",
            body = "{\"content\":\"apiVersion: agentstore/v1\"}",
        )
        assertCommonError(manifestResponse, expectedStatus = 403)

        val revenueResponse = get(path = "/api/developers/$foreignDeveloperId/revenue")
        assertCommonError(revenueResponse, expectedStatus = 403)
    }

    @Test
    fun `demo access issues a one year bearer from an empty request`() {
        val response = sendJson(method = "POST", path = "/api/demo/access", body = "", includeAccess = false)

        assertThat(response.statusCode()).isEqualTo(200)
        val expiresAt = Instant.parse(objectMapper.readTree(response.body()).path("result").path("expiresAt").textValue())
        assertThat(expiresAt).isAfter(Instant.now().plusSeconds(364L * 24 * 60 * 60))
    }

    @Test
    fun `developer mutation requires bearer and rejects another developer agent`() {
        val unauthenticated = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/function-contracts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertCommonError(unauthenticated, expectedStatus = 401)

        val otherUserId = fixtureCleaner.createStandaloneUser()
        val otherDeveloperId = UUID.randomUUID()
        val otherAgentId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into developers (id, user_id, display_name, created_at, updated_at) values (?, ?, ?, current_timestamp, current_timestamp)",
            otherDeveloperId,
            otherUserId,
            "Other developer",
        )
        fixtureCleaner.trackDeveloper(otherDeveloperId)
        jdbcTemplate.update(
            "insert into agents (id, developer_id, code, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
            otherAgentId,
            otherDeveloperId,
            "other-owned-${UUID.randomUUID()}",
            "Other owned agent",
            "Ownership fixture",
        )
        fixtureCleaner.trackAgent(otherAgentId)

        val forbidden = sendJson(
            method = "PATCH",
            path = "/api/agents/$otherAgentId",
            body = "{\"name\":\"Attempted update\"}",
        )
        assertCommonError(forbidden, expectedStatus = 403)
    }

    @Test
    fun `marketplace newest HTTP query returns only active verified agents`() {
        val verifiedCode = "http-verified-${UUID.randomUUID()}"
        insertMarketplaceAgent(
            code = verifiedCode,
            name = "Verified HTTP Agent",
            readinessStatus = AgentVersionReadinessStatus.VERIFIED,
        )
        val unverifiedCode = "http-unverified-${UUID.randomUUID()}"
        insertMarketplaceAgent(
            code = unverifiedCode,
            name = "Unverified HTTP Agent",
            readinessStatus = AgentVersionReadinessStatus.UNVERIFIED,
        )

        val response = get(path = "/api/agents?sort=newest")

        assertThat(response.statusCode()).isEqualTo(200)
        val body = objectMapper.readTree(response.body())
        assertThat(body.path("isSuccess").booleanValue()).isTrue()
        assertThat(body.path("result").path("items").map { item -> item.path("code").textValue() })
            .contains(verifiedCode)
            .doesNotContain(unverifiedCode)
    }

    @Test
    fun `marketplace name HTTP query binds readiness enum on PostgreSQL`() {
        val verifiedCode = "http-name-${UUID.randomUUID()}"
        insertMarketplaceAgent(
            code = verifiedCode,
            name = "Alpha HTTP Agent",
            readinessStatus = AgentVersionReadinessStatus.VERIFIED,
        )

        val response = get(path = "/api/agents?sort=name_asc&q=Alpha")

        assertThat(response.statusCode()).isEqualTo(200)
        val body = objectMapper.readTree(response.body())
        assertThat(body.path("isSuccess").booleanValue()).isTrue()
        assertThat(body.path("result").path("items").map { item -> item.path("code").textValue() })
            .contains(verifiedCode)
    }

    @Test
    fun `marketplace HTTP rejects invalid usage type in common error envelope`() {
        val response = get(path = "/api/agents?usageType=not-a-usage-type")

        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent()
        val body = objectMapper.readTree(response.body())
        assertThat(body.path("isSuccess").booleanValue()).isFalse()
        assertThat(body.path("errorCode").textValue()).isNotBlank()
    }

    @Test
    fun `function contract HTTP operations persist valid schema and reject invalid schema`() {
        val code = "http-contract-${UUID.randomUUID().toString().take(8)}"
        val createResponse = sendJson(
            method = "POST",
            path = "/api/function-contracts",
            body = """
                {"code":"$code","contractVersion":"1.0.0","name":"HTTP contract","description":"PostgreSQL HTTP E2E contract","responseFormat":"JSON","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}},"outputSchema":{"type":"object"}}
            """.trimIndent(),
        )

        assertThat(createResponse.statusCode()).isEqualTo(201)
        assertThat(createResponse.headers().firstValue("X-Trace-Id")).isPresent()
        val created = objectMapper.readTree(createResponse.body())
        assertThat(created.path("isSuccess").booleanValue()).isTrue()
        val contractId = UUID.fromString(created.path("result").path("id").textValue())
        fixtureCleaner.trackFunctionContract(contractId)

        val listResponse = get(path = "/api/function-contracts")
        assertThat(listResponse.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(listResponse.body()).path("result").map { it.path("id").textValue() })
            .contains(contractId.toString())

        val getResponse = get(path = "/api/function-contracts/$contractId")
        assertThat(getResponse.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(getResponse.body()).path("result").path("code").textValue()).isEqualTo(code)

        val providersResponse = get(path = "/api/function-contracts/$contractId/providers")
        assertThat(providersResponse.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(providersResponse.body()).path("result")).isEmpty()

        val invalidSchemaResponse = sendJson(
            method = "POST",
            path = "/api/function-contracts",
            body = """
                {"code":"invalid-$code","contractVersion":"1.0.0","name":"Invalid contract","description":"Invalid output schema","responseFormat":"TEXT","inputSchema":{"type":"object"},"outputSchema":{"type":"object"}}
            """.trimIndent(),
        )
        assertThat(invalidSchemaResponse.statusCode()).isEqualTo(400)
        val invalidBody = objectMapper.readTree(invalidSchemaResponse.body())
        assertThat(invalidBody.path("isSuccess").booleanValue()).isFalse()
        assertThat(invalidBody.path("errorCode").textValue()).isNotBlank()
    }

    @Test
    fun `agent and version HTTP CRUD operations use persisted PostgreSQL fixtures`() {
        val contractId = insertFunctionContract()
        val developerId = DevIdentityInitializer.DEMO_DEVELOPER_ID
        val code = "http-crud-${UUID.randomUUID().toString().take(8)}"
        val createBody = agentPayload(developerId = developerId, code = code, contractId = contractId, semver = "1.0.0")

        val createResponse = sendJson(method = "POST", path = "/api/agents", body = createBody)
        assertThat(createResponse.statusCode()).describedAs(createResponse.body()).isEqualTo(201)
        val created = objectMapper.readTree(createResponse.body())
        assertThat(created.path("isSuccess").booleanValue()).isTrue()
        val agentId = UUID.fromString(created.path("result").path("id").textValue())
        val firstVersionId = UUID.fromString(created.path("result").path("versions")[0].path("id").textValue())
        fixtureCleaner.trackAgent(agentId)
        fixtureCleaner.trackAgentVersion(firstVersionId)

        val getResponse = get("/api/agents/$code")
        assertThat(getResponse.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(getResponse.body()).path("result").path("id").textValue()).isEqualTo(agentId.toString())

        val updateResponse = sendJson(
            method = "PATCH",
            path = "/api/agents/$agentId",
            body = "{\"name\":\"Renamed HTTP Agent\"}",
        )
        assertThat(updateResponse.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(updateResponse.body()).path("result").path("name").textValue())
            .isEqualTo("Renamed HTTP Agent")

        val versionResponse = sendJson(
            method = "POST",
            path = "/api/agents/$agentId/versions",
            body = agentVersionPayload(contractId = contractId, semver = "1.0.1"),
        )
        assertThat(versionResponse.statusCode()).isEqualTo(201)
        val secondVersionId = UUID.fromString(objectMapper.readTree(versionResponse.body()).path("result").path("id").textValue())
        fixtureCleaner.trackAgentVersion(secondVersionId)

        val readinessResponse = get("/api/agent-versions/$firstVersionId/readiness")
        assertThat(readinessResponse.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(readinessResponse.body()).path("result").path("status").textValue()).isEqualTo("UNVERIFIED")

        jdbcTemplate.update(
            "update agent_versions set status = 'ACTIVE'::\"AgentVersionStatus\" where id = ?",
            secondVersionId,
        )
        val disableActiveResponse = sendJson(
            method = "POST",
            path = "/api/agent-versions/$secondVersionId/disable",
            body = "",
        )
        assertThat(disableActiveResponse.statusCode()).describedAs(disableActiveResponse.body()).isEqualTo(200)
        assertThat(objectMapper.readTree(disableActiveResponse.body()).path("result").path("status").textValue())
            .isEqualTo("DISABLED")

        val disableDraftResponse = sendJson(
            method = "POST",
            path = "/api/agent-versions/$firstVersionId/disable",
            body = "",
        )
        assertCommonError(disableDraftResponse, expectedStatus = 409)
        val deleteWithVersionsResponse = sendJson(
            method = "DELETE",
            path = "/api/agents/$agentId",
            body = "",
        )
        assertCommonError(deleteWithVersionsResponse, expectedStatus = 409)

        val bareAgentId = insertBareAgent()
        val deleteBareResponse = sendJson(method = "DELETE", path = "/api/agents/$bareAgentId", body = "")
        assertThat(deleteBareResponse.statusCode()).describedAs(deleteBareResponse.body()).isEqualTo(200)
        val invalidCreateResponse = sendJson(
            method = "POST",
            path = "/api/agents",
            body = "{\"code\":\"missing-required-fields\"}",
        )
        assertCommonError(invalidCreateResponse, expectedStatus = 400)
    }

    @Test
    fun `manifest validate import export and draft replace use the HTTP contract`() {
        val contractId = insertFunctionContract()
        val code = "http-manifest-${UUID.randomUUID().toString().take(8)}"
        val content = manifestContent(contractId = contractId, agentCode = code)

        val validation = sendJson(
            method = "POST",
            path = "/api/agent-manifests/validate",
            body = "{\"content\":${objectMapper.writeValueAsString(content)}}",
            includeAccess = false,
        )
        assertThat(validation.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(validation.body()).path("result").path("agentCode").textValue())
            .isEqualTo(code)

        val imported = sendJson(
            method = "POST",
            path = "/api/agent-manifests",
            body = "{\"content\":${objectMapper.writeValueAsString(content)}}",
        )
        assertThat(imported.statusCode()).describedAs(imported.body()).isEqualTo(201)
        val importedResult = objectMapper.readTree(imported.body()).path("result")
        val agentId = UUID.fromString(importedResult.path("agentId").textValue())
        val versionId = UUID.fromString(importedResult.path("versionId").textValue())
        fixtureCleaner.trackAgent(agentId)
        fixtureCleaner.trackAgentVersion(versionId)

        val exported = get(path = "/api/agent-manifests/agent-versions/$versionId")
        assertThat(exported.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(exported.body()).path("result").path("sha256").textValue())
            .isNotBlank()

        val primaryExported = get(path = "/api/agent-versions/$versionId/manifest")
        assertThat(primaryExported.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(primaryExported.body()).path("result").path("versionId").textValue())
            .isEqualTo(versionId.toString())

        val replaced = sendJson(
            method = "PUT",
            path = "/api/agent-versions/$versionId/manifest",
            body = "{\"content\":${objectMapper.writeValueAsString(content)}}",
        )
        assertThat(replaced.statusCode()).describedAs(replaced.body()).isEqualTo(200)
        assertThat(objectMapper.readTree(replaced.body()).path("result").path("versionId").textValue())
            .isEqualTo(versionId.toString())
    }

    @Test
    fun `dependency CRUD and quote operations use real PostgreSQL ownership and readiness`() {
        val contractId = insertFunctionContract()
        val source = createHttpAgent(contractId = contractId, codePrefix = "http-dependency-source")
        val target = createHttpAgent(contractId = contractId, codePrefix = "http-dependency-target")

        val created = sendJson(
            method = "POST",
            path = "/api/agent-versions/${source.versionId}/dependencies",
            body = """
                {"targetAgentId":"${target.agentId}","versionConstraint":"*","maxPriceAtomic":"1","maxCalls":1}
            """.trimIndent(),
        )
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(201)
        val dependencyId = UUID.fromString(objectMapper.readTree(created.body()).path("result").path("id").textValue())

        val listed = get(path = "/api/agent-versions/${source.versionId}/dependencies")
        assertThat(listed.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(listed.body()).path("result").size()).isEqualTo(1)

        val updated = sendJson(
            method = "PATCH",
            path = "/api/agent-versions/${source.versionId}/dependencies/$dependencyId",
            body = "{\"maxPriceAtomic\":\"2\"}",
        )
        assertThat(updated.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(updated.body()).path("result").path("maxPriceAtomic").textValue())
            .isEqualTo("2")

        val removed = sendJson(
            method = "DELETE",
            path = "/api/agent-versions/${source.versionId}/dependencies/$dependencyId",
            body = "",
        )
        assertThat(removed.statusCode()).isEqualTo(200)

        val quoteFixture = insertMarketplaceAgent(
            code = "http-quote-${UUID.randomUUID().toString().take(8)}",
            name = "HTTP quote fixture",
            readinessStatus = AgentVersionReadinessStatus.VERIFIED,
        )
        val quote = sendJson(
            method = "POST",
            path = "/api/agents/${quoteFixture.code}/quotes",
            body = "{}",
            includeAccess = false,
        )
        assertThat(quote.statusCode()).describedAs(quote.body()).isEqualTo(201)
        val quoteResult = objectMapper.readTree(quote.body()).path("result")
        fixtureCleaner.trackQuote(UUID.fromString(quoteResult.path("id").textValue()))
        assertThat(quoteResult.path("maxCostAtomic").textValue())
            .isEqualTo("1")
    }

    @Test
    fun `execution read and SSE replay expose a terminal event over HTTP`() {
        val fixture = insertMarketplaceAgent(
            code = "http-execution-${UUID.randomUUID().toString().take(8)}",
            name = "HTTP execution fixture",
            readinessStatus = AgentVersionReadinessStatus.VERIFIED,
        )
        val quote = sendJson(
            method = "POST",
            path = "/api/agents/${fixture.code}/quotes",
            body = "{}",
            includeAccess = false,
        )
        assertThat(quote.statusCode()).describedAs(quote.body()).isEqualTo(201)
        val quoteResult = objectMapper.readTree(quote.body()).path("result")
        val quoteId = quoteResult.path("id").textValue()
        val maxCost = quoteResult.path("maxCostAtomic").textValue()
        fixtureCleaner.trackQuote(UUID.fromString(quoteId))

        val created = sendJson(
            method = "POST",
            path = "/api/executions",
            body = "{\"quoteId\":\"$quoteId\",\"maxBudgetAtomic\":\"$maxCost\"}",
            includeAccess = false,
        )
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(202)
        val executionId = UUID.fromString(objectMapper.readTree(created.body()).path("result").path("id").textValue())
        fixtureCleaner.trackExecution(executionId)

        val read = get(path = "/api/executions/$executionId")
        assertThat(read.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(read.body()).path("result").path("id").textValue())
            .isEqualTo(executionId.toString())

        val events = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/api/executions/$executionId/events"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(events.statusCode()).isEqualTo(200)
        assertThat(events.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream")
        assertThat(events.body()).contains("EXECUTION_")
    }

    @Test
    fun `developer revenue and external intent receipt are readable over HTTP`() {
        val runtime = runtimeFixture.create()
        jdbcTemplate.update(
            "update agents set developer_id = ? where id = (select agent_id from agent_versions where id = ?)",
            DevIdentityInitializer.DEMO_DEVELOPER_ID,
            runtime.agentVersionId,
        )
        val paymentAttemptId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into payment_attempts (id, execution_step_id, status, amount_atomic, network, asset, pay_to, transaction_hash, created_at, updated_at) values (?, ?, 'SETTLED'::\"PaymentAttemptStatus\", 7, 'eip155:84532', 'USDC', '0x0000000000000000000000000000000000000001', ?, current_timestamp, current_timestamp)",
            paymentAttemptId,
            runtime.rootStepId,
            "0x${"7".repeat(64)}",
        )
        fixtureCleaner.trackPaymentAttempt(paymentAttemptId)
        val revenueId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into revenue_entries (id, developer_id, execution_step_id, payment_attempt_id, type, amount_atomic, transaction_hash, payment_identifier, created_at, updated_at) values (?, ?, ?, ?, 'DIRECT'::\"RevenueType\", 7, ?, ?, current_timestamp, current_timestamp)",
            revenueId,
            DevIdentityInitializer.DEMO_DEVELOPER_ID,
            runtime.rootStepId,
            paymentAttemptId,
            "0x${"8".repeat(64)}",
            "http-revenue-$revenueId",
        )
        fixtureCleaner.trackRevenueEntry(revenueId)

        val revenue = get(path = "/api/developer/revenue?limit=20")
        assertThat(revenue.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(revenue.body()).path("result").path("totalRevenueAtomic").textValue())
            .isEqualTo("7")
        val ownerRevenue = get(path = "/api/developers/${DevIdentityInitializer.DEMO_DEVELOPER_ID}/revenue?limit=20")
        assertThat(ownerRevenue.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(ownerRevenue.body()).path("isSuccess").booleanValue()).isTrue()
        assertThat(objectMapper.readTree(ownerRevenue.body()).path("result").path("developerId").textValue())
            .isEqualTo(DevIdentityInitializer.DEMO_DEVELOPER_ID.toString())

        val provider = insertMarketplaceAgent(
            code = "http-external-${UUID.randomUUID().toString().take(8)}",
            name = "HTTP external fixture",
            readinessStatus = AgentVersionReadinessStatus.VERIFIED,
        )
        val external = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "http-external-${UUID.randomUUID()}")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"agentCode\":\"${provider.code}\",\"versionConstraint\":\"*\",\"maxTotalAtomic\":\"2\"}",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertThat(external.statusCode()).isEqualTo(402)
        val receipt = external.headers().firstValue("X-AgentStore-Invocation-Receipt").orElseThrow()
        val invocationId = external.headers().firstValue("X-AgentStore-Invocation-Id").orElseThrow()
        assertThat(external.headers().firstValue("Location").orElse("")).contains("/v1/invocations/")
        val externalQuoteId = jdbcTemplate.queryForObject(
            "select quote_id from external_invocation_intents where id = ?",
            UUID::class.java,
            UUID.fromString(invocationId),
        )
        fixtureCleaner.trackQuote(requireNotNull(externalQuoteId))
        fixtureCleaner.trackExternalInvocationIntent(UUID.fromString(invocationId))

        val receiptRead = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations/$invocationId"))
                .header("X-AgentStore-Invocation-Receipt", receipt)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(receiptRead.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(receiptRead.body()).path("result").path("status").textValue())
            .isEqualTo("payment_pending")
    }

    @Test
    fun `external invocation signed retry settles once and exposes receipt SSE over HTTP`() {
        val x402Fixture = LocalX402CertificationFixture(objectMapper)
        try {
            val provider = insertMarketplaceAgent(
                code = "http-external-paid-${UUID.randomUUID().toString().take(8)}",
                name = "HTTP external paid fixture",
                readinessStatus = AgentVersionReadinessStatus.VERIFIED,
            )
            jdbcTemplate.update(
                "update agent_versions set endpoint = ? where id = ?",
                x402Fixture.endpoint,
                provider.versionId,
            )
            val idempotencyKey = "http-external-paid-${UUID.randomUUID()}"
            val body = "{\"agentCode\":\"${provider.code}\",\"versionConstraint\":\"*\",\"maxTotalAtomic\":\"2\"}"
        val pending = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(pending.statusCode()).describedAs(pending.body()).isEqualTo(402)
        val paymentRequired = pending.headers().firstValue("PAYMENT-REQUIRED").orElseThrow()
        val receipt = pending.headers().firstValue("X-AgentStore-Invocation-Receipt").orElseThrow()
        val invocationId = pending.headers().firstValue("X-AgentStore-Invocation-Id").orElseThrow()
        val externalIntentId = UUID.fromString(invocationId)
        val externalQuoteId = jdbcTemplate.queryForObject(
            "select quote_id from external_invocation_intents where id = ?",
            UUID::class.java,
            externalIntentId,
        )
        fixtureCleaner.trackExternalInvocationIntent(externalIntentId)
        fixtureCleaner.trackQuote(requireNotNull(externalQuoteId))

        val codec = X402HeaderCodec(objectMapper)
        val requiredRoot = codec.decodeObject(value = paymentRequired)
        val accepted = requiredRoot.path("accepts").path(0)
        val signedRoot = objectMapper.createObjectNode().apply {
            put("x402Version", 2)
            set<JsonNode>("resource", requiredRoot.path("resource"))
            set<JsonNode>("accepted", accepted)
            set<ObjectNode>("payload", objectMapper.createObjectNode().apply {
                put("signature", "fixture-signature")
                set<ObjectNode>("authorization", objectMapper.createObjectNode().apply {
                    put("from", "0x0000000000000000000000000000000000000003")
                    put("to", "0x0000000000000000000000000000000000000001")
                    put("value", "2")
                    put("validBefore", Instant.now().plusSeconds(60).epochSecond)
                })
            })
        }
        val paid = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("PAYMENT-SIGNATURE", codec.encode(value = signedRoot))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(paid.statusCode()).describedAs(paid.body()).isEqualTo(202)
        assertThat(paid.headers().firstValue("PAYMENT-RESPONSE")).isPresent()
        assertThat(facilitatorFixture.verifyCalls).isEqualTo(1)
        assertThat(facilitatorFixture.settleCalls).isEqualTo(1)
        assertThat(objectMapper.readTree(paid.body()).path("result").path("status").textValue())
            .isEqualTo("execution_created")
        fixtureCleaner.trackExecution(
            UUID.fromString(objectMapper.readTree(paid.body()).path("result").path("executionId").textValue()),
        )

        val receiptRead = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations/$invocationId"))
                .header("X-AgentStore-Invocation-Receipt", receipt)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(receiptRead.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(receiptRead.body()).path("result").path("status").textValue())
            .isEqualTo("execution_created")
        var executionStatus = objectMapper.readTree(receiptRead.body()).path("result").path("executionStatus").textValue()
        for (attempt in 0 until 50) {
            if (executionStatus in setOf("COMPLETED", "FAILED")) {
                break
            }
            Thread.sleep(100)
            val polled = httpClient.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/invocations/$invocationId"))
                    .header("X-AgentStore-Invocation-Receipt", receipt)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            executionStatus = objectMapper.readTree(polled.body()).path("result").path("executionStatus").textValue()
        }
        assertThat(executionStatus).isIn("COMPLETED", "FAILED")

        val events = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/v1/invocations/$invocationId/events"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .header("X-AgentStore-Invocation-Receipt", receipt)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertThat(events.statusCode()).isEqualTo(200)
        assertThat(events.headers().firstValue("Content-Type").orElse(""))
            .contains("text/event-stream")
        val eventBytes = ByteArray(8192)
        val eventLength = events.body().use { stream -> stream.read(eventBytes) }
        assertThat(eventLength).isGreaterThan(0)
        assertThat(eventBytes.copyOf(eventLength).decodeToString()).contains("EXECUTION_CREATED")
        } finally {
            x402Fixture.stop()
        }
    }

    @Test
    fun `legacy active version backfill and verify use local x402 signed retry then expose marketplace agent`() {
        val x402Fixture = LocalX402CertificationFixture(objectMapper)
        try {
            val contractId = insertFunctionContract()
            val code = "http-legacy-verify-${UUID.randomUUID().toString().take(8)}"
            val createResponse = sendJson(
                method = "POST",
                path = "/api/agents",
                body = agentPayload(
                    developerId = DevIdentityInitializer.DEMO_DEVELOPER_ID,
                    code = code,
                    contractId = contractId,
                    semver = "1.0.0",
                ).replace("http://127.0.0.1:8090/agents/http-crud/invoke", x402Fixture.endpoint),
            )
            assertThat(createResponse.statusCode()).describedAs(createResponse.body()).isEqualTo(201)
            val created = objectMapper.readTree(createResponse.body()).path("result")
            val agentId = UUID.fromString(created.path("id").textValue())
            val versionId = UUID.fromString(created.path("versions")[0].path("id").textValue())
            fixtureCleaner.trackAgent(agentId)
            fixtureCleaner.trackAgentVersion(versionId)

            jdbcTemplate.update(
                "update agent_versions set status = 'ACTIVE'::\"AgentVersionStatus\", verification_input = null where id = ?",
                versionId,
            )

            val backfillResponse = sendJson(
                method = "POST",
                path = "/api/agent-versions/$versionId/verification-input/backfill",
                body = "{\"verificationInput\":{\"query\":\"verify\"}}",
            )
            assertThat(backfillResponse.statusCode()).describedAs(backfillResponse.body()).isEqualTo(200)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select verification_input::text from agent_versions where id = ?",
                    String::class.java,
                    versionId,
                ),
            ).contains("verify")

            val repeatedBackfill = sendJson(
                method = "POST",
                path = "/api/agent-versions/$versionId/verification-input/backfill",
                body = "{\"verificationInput\":{\"query\":\"other\"}}",
            )
            assertCommonError(repeatedBackfill, expectedStatus = 409)

            val verifyResponse = sendJson(method = "POST", path = "/api/agent-versions/$versionId/verify", body = "")
            assertThat(verifyResponse.statusCode()).describedAs(verifyResponse.body()).isEqualTo(200)
            assertThat(get(path = "/api/agent-versions/$versionId/readiness").body())
                .contains("\"status\":\"VERIFIED\"")
            assertThat(get(path = "/api/agents?sort=newest").body()).contains(code)

            assertThat(x402Fixture.requests).hasSize(2)
            assertThat(x402Fixture.requests.map { it.idempotencyKey }.distinct()).hasSize(1)
            assertThat(x402Fixture.requests[0].paymentSignature).isNull()
            assertThat(x402Fixture.requests[1].paymentSignature).isNotBlank()
            assertThat(x402Fixture.requests.map { it.body }.distinct()).containsExactly("{\"input\":{\"query\":\"verify\"}}")
            val verifiedRetry = sendJson(method = "POST", path = "/api/agent-versions/$versionId/verify", body = "")
            assertCommonError(verifiedRetry, expectedStatus = 503)
            assertThat(x402Fixture.requests).hasSize(2)
        } finally {
            x402Fixture.stop()
        }
    }

    @Test
    fun `draft publish performs paid certification before activating the version`() {
        val x402Fixture = LocalX402CertificationFixture(objectMapper)
        try {
            val contractId = insertFunctionContract()
            val code = "http-publish-${UUID.randomUUID().toString().take(8)}"
            val createResponse = sendJson(
                method = "POST",
                path = "/api/agents",
                body = agentPayload(
                    developerId = DevIdentityInitializer.DEMO_DEVELOPER_ID,
                    code = code,
                    contractId = contractId,
                    semver = "1.0.0",
                ).replace("http://127.0.0.1:8090/agents/http-crud/invoke", x402Fixture.endpoint),
            )
            assertThat(createResponse.statusCode()).describedAs(createResponse.body()).isEqualTo(201)
            val created = objectMapper.readTree(createResponse.body()).path("result")
            val agentId = UUID.fromString(created.path("id").textValue())
            val versionId = UUID.fromString(created.path("versions")[0].path("id").textValue())
            fixtureCleaner.trackAgent(agentId)
            fixtureCleaner.trackAgentVersion(versionId)

            val published = sendJson(
                method = "POST",
                path = "/api/agent-versions/$versionId/publish",
                body = "",
            )
            assertThat(published.statusCode()).describedAs(published.body()).isEqualTo(200)
            assertThat(jdbcTemplate.queryForObject(
                "select status from agent_versions where id = ?",
                String::class.java,
                versionId,
            )).isEqualTo("ACTIVE")
            assertThat(get(path = "/api/agent-versions/$versionId/readiness").body())
                .contains("\"status\":\"VERIFIED\"")
            assertThat(x402Fixture.requests).hasSize(2)
        } finally {
            x402Fixture.stop()
        }
    }

    @Test
    fun `paid completion persistence failure transitions active version to unknown without retry`() {
        val x402Fixture = LocalX402CertificationFixture(objectMapper)
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val functionName = "block_verified_$suffix"
        val triggerName = "block_verified_$suffix"
        var triggerCreated = false
        try {
            val contractId = insertFunctionContract()
            val code = "http-completion-$suffix"
            val createResponse = sendJson(
                method = "POST",
                path = "/api/agents",
                body = agentPayload(
                    developerId = DevIdentityInitializer.DEMO_DEVELOPER_ID,
                    code = code,
                    contractId = contractId,
                    semver = "1.0.0",
                ).replace("http://127.0.0.1:8090/agents/http-crud/invoke", x402Fixture.endpoint),
            )
            assertThat(createResponse.statusCode()).describedAs(createResponse.body()).isEqualTo(201)
            val created = objectMapper.readTree(createResponse.body()).path("result")
            val agentId = UUID.fromString(created.path("id").textValue())
            val versionId = UUID.fromString(created.path("versions")[0].path("id").textValue())
            fixtureCleaner.trackAgent(agentId)
            fixtureCleaner.trackAgentVersion(versionId)
            jdbcTemplate.update(
                "update agent_versions set status = 'ACTIVE'::\"AgentVersionStatus\" where id = ?",
                versionId,
            )
            jdbcTemplate.execute(
                """
                create function $functionName() returns trigger language plpgsql as $$
                begin
                    if new.status = 'VERIFIED' then
                        raise exception 'fixture blocks verified persistence';
                    end if;
                    return new;
                end;
                $$
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "create trigger $triggerName before update on agent_version_readiness for each row execute function $functionName()",
            )
            triggerCreated = true

            val verifyResponse = sendJson(method = "POST", path = "/api/agent-versions/$versionId/verify", body = "")
            assertCommonError(verifyResponse, expectedStatus = 503)
            assertThat(get(path = "/api/agent-versions/$versionId/readiness").body())
                .contains("\"status\":\"UNKNOWN\"")
            assertThat(x402Fixture.requests).hasSize(2)
            val retryResponse = sendJson(method = "POST", path = "/api/agent-versions/$versionId/verify", body = "")
            assertCommonError(retryResponse, expectedStatus = 503)
            assertThat(x402Fixture.requests).hasSize(2)
        } finally {
            if (triggerCreated) {
                jdbcTemplate.execute("drop trigger if exists $triggerName on agent_version_readiness")
                jdbcTemplate.execute("drop function if exists $functionName()")
            }
            x402Fixture.stop()
        }
    }

    @Test
    fun `verified readiness preflight failure is persisted as unavailable and restart recovery as unknown`() {
        val failingFixture = LocalPreflightFailureFixture()
        try {
            val contractId = insertFunctionContract()
            val agent = createHttpAgent(contractId = contractId, codePrefix = "http-preflight")
            jdbcTemplate.update(
                "update agent_versions set status = 'ACTIVE'::\"AgentVersionStatus\", endpoint = ?, verification_input = '{}'::jsonb where id = ?",
                failingFixture.endpoint,
                agent.versionId,
            )
            jdbcTemplate.update(
                "update agent_version_readiness set status = 'VERIFIED'::\"AgentVersionReadinessStatus\" where version_id = ?",
                agent.versionId,
            )

            readinessService.preflightVerifiedProviders()
            val unavailable = get(path = "/api/agent-versions/${agent.versionId}/readiness")
            assertThat(unavailable.statusCode()).isEqualTo(200)
            assertThat(objectMapper.readTree(unavailable.body()).path("result").path("status").textValue())
                .isEqualTo("UNAVAILABLE")

            jdbcTemplate.update(
                "update agent_version_readiness set status = 'VERIFYING'::\"AgentVersionReadinessStatus\" where version_id = ?",
                agent.versionId,
            )
            readinessService.recoverInterruptedCertifications()
            val recovered = get(path = "/api/agent-versions/${agent.versionId}/readiness")
            assertThat(objectMapper.readTree(recovered.body()).path("result").path("status").textValue())
                .isEqualTo("UNKNOWN")
        } finally {
            failingFixture.stop()
        }
    }

    @Test
    fun `known paid provider failure marks active version unknown without another payment`() {
        val failingFixture = LocalX402CertificationFixture(objectMapper, paidStatus = 503)
        try {
            val contractId = insertFunctionContract()
            val agent = createHttpAgent(contractId = contractId, codePrefix = "http-known-failure")
            jdbcTemplate.update(
                "update agent_versions set status = 'ACTIVE'::\"AgentVersionStatus\", endpoint = ? where id = ?",
                failingFixture.endpoint,
                agent.versionId,
            )
            val verify = sendJson(method = "POST", path = "/api/agent-versions/${agent.versionId}/verify", body = "")
            assertCommonError(verify, expectedStatus = 503)
            assertThat(objectMapper.readTree(get(path = "/api/agent-versions/${agent.versionId}/readiness").body()).path("result").path("status").textValue())
                .isEqualTo("UNKNOWN")
            assertThat(failingFixture.requests).hasSize(2)
        } finally {
            failingFixture.stop()
        }
    }

    @Test
    fun `concurrent active verification claims one certification and never double pays`() {
        val x402Fixture = LocalX402CertificationFixture(objectMapper)
        try {
            val contractId = insertFunctionContract()
            val agent = createHttpAgent(contractId = contractId, codePrefix = "http-concurrent")
            jdbcTemplate.update(
                "update agent_versions set status = 'ACTIVE'::\"AgentVersionStatus\", endpoint = ? where id = ?",
                x402Fixture.endpoint,
                agent.versionId,
            )
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            val responses = try {
                List(2) {
                    executor.submit<HttpResponse<String>> {
                        sendJson(method = "POST", path = "/api/agent-versions/${agent.versionId}/verify", body = "")
                    }
                }.map { future -> future.get() }
            } finally {
                executor.shutdownNow()
            }
            assertThat(responses.map(HttpResponse<String>::statusCode)).contains(200)
            assertThat(responses.count { it.statusCode() != 200 }).isEqualTo(1)
            assertThat(x402Fixture.requests).hasSize(2)
        } finally {
            x402Fixture.stop()
        }
    }

    private fun get(path: String): HttpResponse<String> {
        return getWithToken(path = path, token = accessToken)
    }

    private fun getWithToken(path: String, token: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port$path"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun sendJson(
        method: String,
        path: String,
        body: String,
        includeAccess: Boolean = true,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
        if (includeAccess) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        val request = builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun sendJsonWithAuthorization(
        method: String,
        path: String,
        body: String,
        authorization: String,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", authorization)
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun assertCommonError(response: HttpResponse<String>, expectedStatus: Int) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus)
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent()
        val body = objectMapper.readTree(response.body())
        assertThat(body.path("isSuccess").booleanValue()).isFalse()
        assertThat(body.path("errorCode").textValue()).isNotBlank()
    }

    private fun insertBareAgent(): UUID {
        val agentId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into agents (id, developer_id, code, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'INTERNAL_COMPONENT'::\"AgentUsageType\", current_timestamp, current_timestamp)",
            agentId,
            DevIdentityInitializer.DEMO_DEVELOPER_ID,
            "http-bare-${UUID.randomUUID().toString().take(8)}",
            "HTTP bare agent",
            "Agent without a version for delete success",
        )
        fixtureCleaner.trackAgent(agentId)
        return agentId
    }

    private fun insertFunctionContract(): UUID {
        val contractId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into function_contracts (id, code, contract_version, name, description, response_format, input_schema, output_schema, created_at, updated_at) values (?, ?, '1.0.0', ?, ?, 'JSON'::\"AgentResponseFormat\", ?::jsonb, ?::jsonb, current_timestamp, current_timestamp)",
            contractId,
            "http-contract-${contractId.toString().take(8)}",
            "HTTP fixture contract",
            "Contract used by the actual HTTP CRUD test",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\"}",
        )
        fixtureCleaner.trackFunctionContract(contractId)
        return contractId
    }

    private fun manifestContent(contractId: UUID, agentCode: String): String {
        val contractCode = "http-contract-${contractId.toString().take(8)}"
        return """
            apiVersion: agentstore/v1
            agent:
              developerId: ${DevIdentityInitializer.DEMO_DEVELOPER_ID}
              code: $agentCode
              name: HTTP Manifest Agent
              description: Manifest HTTP fixture
              version: 1.0.0
              usageType: internal_component
              function:
                code: $contractCode
                version: 1.0.0
              endpoint: http://127.0.0.1:8090/agents/http-manifest/invoke
              payment:
                priceAtomic: "1"
                network: eip155:84532
                asset: 0x036CbD53842c5426634e7929541eC2318f3dCF7e
                payTo: "0x0000000000000000000000000000000000000001"
              verificationInput:
                query: verify
            dependencies: []
        """.trimIndent()
    }

    private fun createHttpAgent(contractId: UUID, codePrefix: String): HttpAgentFixture {
        val code = "$codePrefix-${UUID.randomUUID().toString().take(8)}"
        val response = sendJson(
            method = "POST",
            path = "/api/agents",
            body = agentPayload(
                developerId = DevIdentityInitializer.DEMO_DEVELOPER_ID,
                code = code,
                contractId = contractId,
                semver = "1.0.0",
            ),
        )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(201)
        val result = objectMapper.readTree(response.body()).path("result")
        val agentId = UUID.fromString(result.path("id").textValue())
        val versionId = UUID.fromString(result.path("versions")[0].path("id").textValue())
        fixtureCleaner.trackAgent(agentId)
        fixtureCleaner.trackAgentVersion(versionId)
        return HttpAgentFixture(agentId = agentId, versionId = versionId, code = code)
    }

    private fun agentPayload(developerId: UUID, code: String, contractId: UUID, semver: String): String {
        return """
            {"developerId":"$developerId","code":"$code","name":"HTTP CRUD Agent","description":"Actual HTTP CRUD fixture","semver":"$semver","endpoint":"http://127.0.0.1:8090/agents/http-crud/invoke","priceAtomic":"1","network":"eip155:84532","asset":"0x036CbD53842c5426634e7929541eC2318f3dCF7e","payTo":"0x0000000000000000000000000000000000000001","responseFormat":"JSON","functionContractId":"$contractId","verificationInput":{"query":"verify"},"usageType":"internal_component"}
        """.trimIndent()
    }

    private fun agentVersionPayload(contractId: UUID, semver: String): String {
        return """
            {"semver":"$semver","endpoint":"http://127.0.0.1:8090/agents/http-crud/invoke","priceAtomic":"1","network":"eip155:84532","asset":"0x036CbD53842c5426634e7929541eC2318f3dCF7e","payTo":"0x0000000000000000000000000000000000000001","responseFormat":"JSON","functionContractId":"$contractId","verificationInput":{"query":"verify"}}
        """.trimIndent()
    }

    private fun insertMarketplaceAgent(
        code: String,
        name: String,
        readinessStatus: AgentVersionReadinessStatus,
    ): HttpAgentFixture {
        val userId = fixtureCleaner.createStandaloneUser()
        val developerId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val versionId = UUID.randomUUID()

        jdbcTemplate.update(
            "insert into developers (id, user_id, display_name, created_at, updated_at) values (?, ?, ?, current_timestamp, current_timestamp)",
            developerId,
            userId,
            "HTTP integration developer",
        )
        fixtureCleaner.trackDeveloper(developerId)
        jdbcTemplate.update(
            "insert into agents (id, developer_id, code, name, description, usage_type, created_at, updated_at) values (?, ?, ?, ?, ?, 'USER_FACING'::\"AgentUsageType\", current_timestamp, current_timestamp)",
            agentId,
            developerId,
            code,
            name,
            "HTTP integration marketplace agent",
        )
        fixtureCleaner.trackAgent(agentId)
        jdbcTemplate.update(
            "insert into agent_versions (id, agent_id, semver, status, endpoint, price_atomic, network, asset, pay_to, response_format, created_at, updated_at) values (?, ?, '1.0.0', 'ACTIVE'::\"AgentVersionStatus\", 'http://127.0.0.1:8090/agents/http/invoke', 1, 'eip155:84532', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', '0x0000000000000000000000000000000000000001', 'JSON'::\"AgentResponseFormat\", current_timestamp, current_timestamp)",
            versionId,
            agentId,
        )
        fixtureCleaner.trackAgentVersion(versionId)
        jdbcTemplate.update(
            "insert into agent_version_readiness (version_id, status, created_at, updated_at) values (?, ?::\"AgentVersionReadinessStatus\", current_timestamp, current_timestamp)",
            versionId,
            readinessStatus.name,
        )
        return HttpAgentFixture(agentId = agentId, versionId = versionId, code = code)
    }

    private data class HttpAgentFixture(
        val agentId: UUID,
        val versionId: UUID,
        val code: String,
    )

    private class LocalPreflightFailureFixture {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = "http://127.0.0.1:${server.address.port}/preflight"

        init {
            server.createContext("/preflight") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            server.start()
        }

        fun stop() {
            server.stop(0)
        }
    }

    private class LocalX402CertificationFixture(
        private val objectMapper: ObjectMapper,
        private val paidStatus: Int = 200,
        private val includeReceipt: Boolean = true,
    ) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val transactionHash = "0x${UUID.randomUUID().toString().replace("-", "")}${"1".repeat(32)}"
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val endpoint = "http://127.0.0.1:${server.address.port}/certify"

        init {
            server.createContext("/certify") { exchange ->
                val signature = exchange.requestHeaders.getFirst("PAYMENT-SIGNATURE")
                requests += Request(
                    idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key"),
                    paymentSignature = signature,
                    body = exchange.requestBody.readBytes().decodeToString(),
                )
                exchange.responseHeaders.add("Content-Type", "application/json")
                if (signature == null) {
                    exchange.responseHeaders.add("PAYMENT-REQUIRED", encodedRequirements())
                    exchange.responseHeaders.add("Access-Control-Expose-Headers", "PAYMENT-REQUIRED")
                    exchange.sendResponseHeaders(402, 2)
                    exchange.responseBody.use { it.write("{}".encodeToByteArray()) }
                } else {
                    if (includeReceipt) {
                        exchange.responseHeaders.add("PAYMENT-RESPONSE", encodedReceipt())
                    }
                    exchange.sendResponseHeaders(paidStatus, 2)
                    exchange.responseBody.use { it.write("{}".encodeToByteArray()) }
                }
            }
            server.start()
        }

        fun stop() {
            server.stop(0)
        }

        private fun encodedRequirements(): String {
            val required = mapOf(
                "x402Version" to 2,
                "resource" to mapOf("url" to endpoint),
                "accepts" to listOf(
                    mapOf(
                        "scheme" to "exact",
                        "network" to "eip155:84532",
                        "amount" to "1",
                        "asset" to "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
                        "payTo" to "0x0000000000000000000000000000000000000001",
                        "maxTimeoutSeconds" to 60,
                        "extra" to mapOf(
                            "assetTransferMethod" to "eip3009",
                            "name" to "USDC",
                            "version" to "2",
                        ),
                    ),
                ),
            )
            return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(required))
        }

        private fun encodedReceipt(): String {
            return Base64.getEncoder().encodeToString(
                objectMapper.writeValueAsBytes(
                    mapOf(
                        "success" to true,
                        "network" to "eip155:84532",
                        "transaction" to transactionHash,
                    ),
                ),
            )
        }

        data class Request(
            val idempotencyKey: String?,
            val paymentSignature: String?,
            val body: String,
        )
    }

}

@TestConfiguration(proxyBeanMethods = false)
class ExternalPaymentFixtureConfiguration {
    @Bean
    @Primary
    fun facilitatorFixture(): DeterministicFacilitatorFixture {
        return DeterministicFacilitatorFixture()
    }
}

class DeterministicFacilitatorFixture : FacilitatorIncomingPaymentGateway {
    var verifyCalls: Int = 0
        private set
    var settleCalls: Int = 0
        private set

    override fun verify(
        paymentPayload: ObjectNode,
        paymentRequirement: ObjectNode,
    ): IncomingPaymentVerificationDto {
        verifyCalls += 1
        return IncomingPaymentVerificationDto(payer = "0x0000000000000000000000000000000000000004")
    }

    override fun settle(
        paymentPayload: ObjectNode,
        paymentRequirement: ObjectNode,
    ): IncomingPaymentSettlementDto {
        settleCalls += 1
        return IncomingPaymentSettlementDto(
            payer = "0x0000000000000000000000000000000000000004",
            transactionHash = "0x${UUID.randomUUID().toString().replace("-", "")}${"0".repeat(32)}",
        )
    }
}
