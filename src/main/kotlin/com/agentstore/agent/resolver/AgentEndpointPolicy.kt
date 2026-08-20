package com.agentstore.agent.resolver

import com.agentstore.agent.model.vo.ValidatedAgentEndpoint
import com.agentstore.common.exception.ApiException
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URI
import java.util.*

@Component
class AgentEndpointPolicy(
    private val environment: Environment,
    private val addressResolver: AgentEndpointAddressResolver,
) {
    fun validate(endpoint: String) {
        resolve(endpoint)
    }

    fun resolve(endpoint: String): ValidatedAgentEndpoint {
        val uri = runCatching { URI(endpoint) }.getOrElse {
            throw invalid("endpoint must be an absolute HTTP(S) URL")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT)
        if (!uri.isAbsolute || scheme !in HTTP_SCHEMES || host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            throw invalid("endpoint must be an absolute HTTP(S) URL without credentials or fragments")
        }

        if (isDevelopmentProfile()) {
            if (host !in DEVELOPMENT_LOOPBACK_HOSTS) {
                throw unsafe("development only permits loopback Agent endpoints")
            }
            return ValidatedAgentEndpoint(uri, developmentLoopbackAddress(host))
        }

        if (scheme != "https") {
            throw unsafe("production Agent endpoints must use HTTPS")
        }
        val addresses = runCatching { addressResolver.resolve(host) }.getOrElse {
            throw unsafe("Agent endpoint host could not be resolved")
        }
        if (addresses.isEmpty() || addresses.any { !isPubliclyRoutable(it) }) {
            throw unsafe("Agent endpoint must resolve exclusively to publicly routable addresses")
        }
        return ValidatedAgentEndpoint(uri, addresses)
    }

    private fun isDevelopmentProfile(): Boolean {
        return environment.matchesProfiles("dev", "development", "test")
    }

    private fun developmentLoopbackAddress(host: String): List<java.net.InetAddress> {
        return when (host) {
            "::1" -> listOf(
                java.net.InetAddress.getByAddress(
                    byteArrayOf(
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        1
                    )
                )
            )

            else -> listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
    }

    private fun isPubliclyRoutable(address: java.net.InetAddress): Boolean {
        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false
        }
        if (first == 100 && second in 64..127) {
            return false
        }
        if (first == 169 && second == 254) {
            return false
        }
        if (first == 172 && second in 16..31) {
            return false
        }
        if (first == 192 && second == 0) {
            return false
        }
        if (first == 192 && second == 88 && third == 99) {
            return false
        }
        if (first == 192 && second == 168) {
            return false
        }
        if (first == 198 && second in 18..19) {
            return false
        }
        if (first == 198 && second == 51 && third == 100) {
            return false
        }
        if (first == 203 && second == 0 && third == 113) {
            return false
        }
        return true
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first !in 0x20..0x3f) {
            return false
        }
        if (first == 0x20 && second == 0x01 && bytes[2].toInt() and 0xff == 0x0d && bytes[3].toInt() and 0xff == 0xb8) {
            return false
        }
        return true
    }

    private fun invalid(message: String): ApiException {
        return ApiException("INVALID_ENDPOINT", message, 400)
    }

    private fun unsafe(message: String): ApiException {
        return ApiException("UNSAFE_AGENT_ENDPOINT", message, 400)
    }

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
        val DEVELOPMENT_LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
