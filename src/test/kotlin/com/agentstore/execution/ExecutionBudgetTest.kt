package com.agentstore.execution

import com.agentstore.execution.model.entity.Execution
import com.agentstore.execution.model.vo.ExecutionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.*

class ExecutionBudgetTest {
    @Test
    fun `reservation is bounded by actual plus reserved cost`() {
        val execution = Execution(UUID.randomUUID(), UUID.randomUUID(), BigInteger.TEN, null, null)
        execution.reserve(BigInteger.valueOf(6))
        execution.reserve(BigInteger.valueOf(4))

        assertEquals(BigInteger.TEN, execution.reservedCostAtomic)
        assertThrows(IllegalStateException::class.java) { execution.reserve(BigInteger.ONE) }
    }

    @Test
    fun `settlement moves reserved cost to actual cost and completion preserves total`() {
        val execution = Execution(UUID.randomUUID(), UUID.randomUUID(), BigInteger.TEN, null, null)
        execution.start()
        execution.reserve(BigInteger.valueOf(4))
        execution.settle(BigInteger.valueOf(4))
        execution.complete()

        assertEquals(BigInteger.ZERO, execution.reservedCostAtomic)
        assertEquals(BigInteger.valueOf(4), execution.actualCostAtomic)
        assertEquals(ExecutionStatus.COMPLETED, execution.status)
    }
}
