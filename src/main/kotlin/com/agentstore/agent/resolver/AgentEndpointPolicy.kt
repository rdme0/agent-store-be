package com.agentstore.agent.resolver

import com.agentstore.agent.model.vo.ValidatedAgentEndpoint
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class AgentEndpointPolicy(
    private val environment: Environment,
    private val addressResolver: AgentEndpointAddressResolver,
) {
    private companion object {
        private val IPV6_LOOPBACK = ByteArray(16).apply { this[lastIndex] = 1 }
        private val IPV4_LOOPBACK = byteArrayOf(127, 0, 0, 1)
        private val HTTP_SCHEMES = setOf("http", "https")
        private val DEVELOPMENT_AGENT_HOSTS = setOf("localhost", "127.0.0.1", "::1", "demo-agent")
    }

    fun validate(endpoint: String) {
        resolve(endpoint)
    }

    fun resolve(endpoint: String): ValidatedAgentEndpoint {
        val uri = runCatching { URI(endpoint) }.getOrElse {
            throw DomainClientException(
                errorCode = ErrorCode.INVALID_ENDPOINT,
                messageArguments = arrayOf("endpoint must be an absolute HTTP(S) URL"),
            )
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT)
        val hasInvalidStructure = !uri.isAbsolute ||
            scheme !in HTTP_SCHEMES ||
            host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null
        if (hasInvalidStructure) {
            throw DomainClientException(
                errorCode = ErrorCode.INVALID_ENDPOINT,
                messageArguments = arrayOf(
                    "endpoint must be an absolute HTTP(S) URL without credentials or fragments",
                ),
            )
        }

        if (isDevelopmentProfile()) {
            if (host !in DEVELOPMENT_AGENT_HOSTS) {
                throw DomainClientException(
                    errorCode = ErrorCode.UNSAFE_AGENT_ENDPOINT,
                    messageArguments = arrayOf("development only permits loopback Agent endpoints"),
                )
            }
            return ValidatedAgentEndpoint(uri = uri, addresses = developmentAddresses(host = host))
        }

        if (scheme != "https") {
            throw DomainClientException(
                errorCode = ErrorCode.UNSAFE_AGENT_ENDPOINT,
                messageArguments = arrayOf("production Agent endpoints must use HTTPS"),
            )
        }
        val addresses = runCatching { addressResolver.resolve(host) }.getOrElse {
            throw DomainClientException(
                errorCode = ErrorCode.UNSAFE_AGENT_ENDPOINT,
                messageArguments = arrayOf("Agent endpoint host could not be resolved"),
            )
        }
        if (addresses.isEmpty() || addresses.any { !isPubliclyRoutable(it) }) {
            throw DomainClientException(
                errorCode = ErrorCode.UNSAFE_AGENT_ENDPOINT,
                messageArguments = arrayOf(
                    "Agent endpoint must resolve exclusively to publicly routable addresses",
                ),
            )
        }
        return ValidatedAgentEndpoint(uri = uri, addresses = addresses)
    }

    private fun isDevelopmentProfile(): Boolean {
        return environment.matchesProfiles("dev", "development", "test")
    }

    private fun developmentAddresses(host: String): List<InetAddress> {
        return when (host) {
            "::1" -> listOf(InetAddress.getByAddress(IPV6_LOOPBACK))
            "localhost", "127.0.0.1" -> listOf(InetAddress.getByAddress(IPV4_LOOPBACK))
            else -> runCatching { addressResolver.resolve(host) }.getOrElse {
                throw DomainClientException(
                    errorCode = ErrorCode.UNSAFE_AGENT_ENDPOINT,
                    messageArguments = arrayOf("development Agent endpoint host could not be resolved"),
                )
            }.takeIf(List<InetAddress>::isNotEmpty) ?: throw DomainClientException(
                errorCode = ErrorCode.UNSAFE_AGENT_ENDPOINT,
                messageArguments = arrayOf("development Agent endpoint host could not be resolved"),
            )
        }
    }

    private fun isPubliclyRoutable(address: InetAddress): Boolean {
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
        val isDocumentationPrefix = first == 0x20 &&
            second == 0x01 &&
            bytes[2].toInt() and 0xff == 0x0d &&
            bytes[3].toInt() and 0xff == 0xb8
        if (isDocumentationPrefix) {
            return false
        }
        return true
    }

}
