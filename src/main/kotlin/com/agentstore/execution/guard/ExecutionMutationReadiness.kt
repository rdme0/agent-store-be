package com.agentstore.execution.guard

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.stereotype.Component

/** Blocks execution-changing requests until restart payment recovery has established durable state. */
@Component
class ExecutionMutationReadiness {
    private val ready = AtomicBoolean(false)

    fun requireReady() {
        if (!ready.get()) {
            throw DomainClientException(ErrorCode.EXECUTION_RECOVERY_IN_PROGRESS)
        }
    }

    fun markReady() {
        ready.set(true)
    }
}
