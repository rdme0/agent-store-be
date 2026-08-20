package com.agentstore.payment.client

import com.agentstore.agent.model.vo.ValidatedAgentEndpoint
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

class PinnedAgentDnsResolverTest {
    @Test
    fun `resolver returns only validated endpoint addresses`() {
        val pinned = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val resolver =
            PinnedAgentDnsResolver(ValidatedAgentEndpoint(URI("https://agent.example.com/invoke"), listOf(pinned)))

        assertArrayEquals(arrayOf(pinned), resolver.resolve("agent.example.com"))
        assertThrows(UnknownHostException::class.java) { resolver.resolve("redirect.example.com") }
    }
}
