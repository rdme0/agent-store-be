package com.agentstore.payment.config

import com.agentstore.dependency.model.vo.ExecutionGraphLimits
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agent-store.x402-client")
data class X402ClientProperties(
    val perDepthInvocationTimeout: Duration,
) {
    init {
        require(!perDepthInvocationTimeout.isZero && !perDepthInvocationTimeout.isNegative) {
            "agent-store.x402-client.per-depth-invocation-timeout is invalid"
        }
    }

    fun aggregateInvocationTimeout(): Duration {
        return perDepthInvocationTimeout.multipliedBy(ExecutionGraphLimits.MAX_DEPTH.toLong())
    }

    fun invocationTimeout(callPathSize: Int): Duration {
        require(callPathSize in 1..ExecutionGraphLimits.MAX_DEPTH) {
            "execution call path depth is invalid"
        }
        val remainingDepth = ExecutionGraphLimits.MAX_DEPTH - callPathSize + 1
        return perDepthInvocationTimeout.multipliedBy(remainingDepth.toLong())
    }
}
