package com.agentstore.external.config

import com.agentstore.agent.service.FunctionContractService
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.dependency.service.QuoteService
import com.agentstore.execution.service.ExecutionService
import com.agentstore.external.client.FacilitatorIncomingPaymentClient
import com.agentstore.external.client.FacilitatorIncomingPaymentGateway
import com.agentstore.external.repository.ExternalApiSaleRepository
import com.agentstore.external.repository.ExternalInvocationIntentRepository
import com.agentstore.external.service.ExternalIntentRateLimiter
import com.agentstore.external.service.ExternalInvocationService
import com.agentstore.external.service.ExternalX402PaymentService
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class ExternalApiConfiguration {
    companion object {
        private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    }

    @Bean
    fun externalX402PaymentService(
        properties: ExternalApiProperties,
        facilitatorClient: FacilitatorIncomingPaymentGateway,
        objectMapper: ObjectMapper,
    ): ExternalX402PaymentService {
        validate(properties = properties)
        return ExternalX402PaymentService(
            properties = properties,
            facilitatorClient = facilitatorClient,
            objectMapper = objectMapper,
        )
    }

    @Bean
    fun facilitatorIncomingPaymentClient(
        properties: ExternalApiProperties,
        objectMapper: ObjectMapper,
    ): FacilitatorIncomingPaymentClient {
        val uri = URI(properties.facilitatorUrl.trimEnd('/') + "/")
        validateHttps(uri = uri)
        return FacilitatorIncomingPaymentClient(
            facilitatorBaseUri = uri,
            requestTimeout = properties.facilitatorRequestTimeout,
            httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.facilitatorRequestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            objectMapper = objectMapper,
        )
    }

    @Bean
    fun externalInvocationService(
        properties: ExternalApiProperties,
        agentStoreProperties: AgentStoreProperties,
        intentRepository: ExternalInvocationIntentRepository,
        saleRepository: ExternalApiSaleRepository,
        quoteService: QuoteService,
        executionService: ExecutionService,
        functionContractService: FunctionContractService,
        x402PaymentService: ExternalX402PaymentService,
        objectMapper: ObjectMapper,
        transactionTemplate: TransactionTemplate,
    ): ExternalInvocationService {
        return ExternalInvocationService(
            properties = properties,
            agentStoreProperties = agentStoreProperties,
            intentRepository = intentRepository,
            saleRepository = saleRepository,
            quoteService = quoteService,
            executionService = executionService,
            functionContractService = functionContractService,
            x402PaymentService = x402PaymentService,
            objectMapper = objectMapper,
            transactionTemplate = transactionTemplate,
            clock = Clock.systemUTC(),
        )
    }

    @Bean
    fun externalIntentRateLimiter(properties: ExternalApiProperties): ExternalIntentRateLimiter {
        return ExternalIntentRateLimiter(properties = properties, clock = Clock.systemUTC())
    }

    private fun validate(properties: ExternalApiProperties) {
        validateHttps(uri = URI(properties.publicBaseUrl))
        require(EVM_ADDRESS.matches(properties.payTo)) { "agent-store.external-api.pay-to is invalid" }
        require(properties.feeBasisPoints in 0..10_000) { "agent-store.external-api.fee-basis-points is invalid" }
        require(!properties.facilitatorRequestTimeout.isZero && !properties.facilitatorRequestTimeout.isNegative) {
            "agent-store.external-api.facilitator-request-timeout is invalid"
        }
        require(!properties.authorizationTimeout.isZero && !properties.authorizationTimeout.isNegative) {
            "agent-store.external-api.authorization-timeout is invalid"
        }
        require(!properties.intentTtl.isZero && !properties.intentTtl.isNegative) {
            "agent-store.external-api.intent-ttl is invalid"
        }
        require(!properties.receiptTtl.isZero && !properties.receiptTtl.isNegative) {
            "agent-store.external-api.receipt-ttl is invalid"
        }
        require(properties.rateLimitPerMinute > 0) { "agent-store.external-api.rate-limit-per-minute is invalid" }
    }

    private fun validateHttps(uri: URI) {
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port == 443)) {
            "external x402 URL must use HTTPS default port"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "external x402 URL is invalid"
        }
    }
}
