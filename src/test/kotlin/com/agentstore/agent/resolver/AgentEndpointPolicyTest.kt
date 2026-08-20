package com.agentstore.agent.resolver

import com.agentstore.common.exception.client.DomainClientException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.net.InetAddress
import java.net.UnknownHostException

class AgentEndpointPolicyTest {
    @Test
    fun `development accepts only explicit loopback hosts`() {
        val policy = policy("dev") { error("development loopback validation must not resolve DNS") }

        assertDoesNotThrow { policy.validate("http://localhost:8090/invoke") }
        assertDoesNotThrow { policy.validate("https://127.0.0.1:8090/invoke") }
        assertDoesNotThrow { policy.validate("http://[::1]:8090/invoke") }
        assertUnsafe { policy.validate("https://agent.example.com/invoke") }
        assertUnsafe { policy.validate("http://127.0.0.2/invoke") }
        assertUnsafe { policy.validate("http://localhost.:8090/invoke") }
    }

    @Test
    fun `production requires HTTPS and resolves every address as public`() {
        val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val policy = policy("prod") { listOf(public) }

        assertDoesNotThrow { policy.validate("https://agent.example.com/invoke") }
        assertUnsafe { policy.validate("http://agent.example.com/invoke") }
    }

    @Test
    fun `production rejects private reserved and mixed DNS addresses`() {
        val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val private = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7))
        val documentation = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7))

        assertUnsafe { policy("prod") { listOf(private) }.validate("https://agent.example.com") }
        assertUnsafe { policy("prod") { listOf(documentation) }.validate("https://agent.example.com") }
        assertUnsafe { policy("prod") { listOf(public, private) }.validate("https://agent.example.com") }
    }

    @Test
    fun `production fails closed when DNS resolution fails or endpoint is malformed`() {
        assertUnsafe { policy("prod") { throw UnknownHostException("agent.example.com") }.validate("https://agent.example.com") }
        assertInvalid { policy("prod") { emptyList() }.validate("https://user:password@agent.example.com") }
        assertInvalid { policy("prod") { emptyList() }.validate("file:///tmp/agent") }
    }

    @Test
    fun `production accepts globally routable IPv6 and rejects documentation IPv6`() {
        val publicV6 = InetAddress.getByName("2606:4700:4700::1111")
        val documentationV6 = InetAddress.getByName("2001:db8::1")

        assertDoesNotThrow { policy("production") { listOf(publicV6) }.validate("https://agent.example.com") }
        assertUnsafe { policy("production") { listOf(documentationV6) }.validate("https://agent.example.com") }
    }

    private fun policy(profile: String, resolver: AgentEndpointAddressResolver): AgentEndpointPolicy {
        return AgentEndpointPolicy(MockEnvironment().apply { setActiveProfiles(profile) }, resolver)
    }

    private fun assertUnsafe(action: () -> Unit) {
        assertEquals("AGENT_400_005", assertThrows(DomainClientException::class.java, action).errorCode.code)
    }

    private fun assertInvalid(action: () -> Unit) {
        assertEquals("AGENT_400_002", assertThrows(DomainClientException::class.java, action).errorCode.code)
    }
}
