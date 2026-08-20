package com.agentstore.execution.guard

import com.agentstore.common.exception.ApiException
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** Blocks execution-changing requests until restart payment recovery has established durable state. */
@Component
class ExecutionMutationReadiness {
    private val ready = AtomicBoolean(false)

    fun requireReady() {
        if (!ready.get()) {
            throw ApiException(
                "EXECUTION_RECOVERY_IN_PROGRESS",
                "Execution mutations are unavailable while payment recovery is running",
                503
            )
        }
    }

    fun markReady() {
        ready.set(true)
    }
}
