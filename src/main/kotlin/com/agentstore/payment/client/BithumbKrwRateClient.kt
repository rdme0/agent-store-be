package com.agentstore.payment.client

import com.agentstore.common.config.AgentStoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BithumbKrwRateClient(
    private val objectMapper: ObjectMapper,
    private val apiUrl: URI,
    private val timeout: Duration,
    private val httpClient: HttpClient,
) {
    companion object {
        const val MAX_RESPONSE_BYTES = 65_536

        fun create(properties: AgentStoreProperties, objectMapper: ObjectMapper): BithumbKrwRateClient {
            val uri = URI(properties.bithumbApiUrl)
            require(
                uri.scheme == "https" &&
                    uri.host == "api.bithumb.com" &&
                    (uri.port == -1 || uri.port == 443) &&
                    uri.userInfo == null &&
                    uri.fragment == null &&
                    (uri.path.isBlank() || uri.path == "/"),
            ) { "invalid_bithumb_api_url" }
            val client = HttpClient.newBuilder()
                .connectTimeout(properties.bithumbRequestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            return BithumbKrwRateClient(
                objectMapper = objectMapper,
                apiUrl = uri,
                timeout = properties.bithumbRequestTimeout,
                httpClient = client,
            )
        }
    }

    fun currentUsdcKrwRate(): BigDecimal {
        val request = HttpRequest.newBuilder(apiUrl.resolve("/v1/ticker?markets=KRW-USDC"))
            .GET()
            .timeout(timeout)
            .header("Accept", "application/json")
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() == 200) { "bithumb_ticker_status_${response.statusCode()}" }

        val payload = response.body().readBounded()
        val ticker = objectMapper.readTree(payload).firstOrNull()
            ?: error("bithumb_ticker_missing")
        val rate = ticker.path("trade_price").decimalValue()
        require(rate > BigDecimal.ZERO) { "bithumb_ticker_invalid_rate" }
        return rate
    }

    private fun InputStream.readBounded(): ByteArray {
        return use { stream ->
            val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
            require(bytes.size <= MAX_RESPONSE_BYTES) { "bithumb_ticker_response_too_large" }
            return@use bytes
        }
    }

}
