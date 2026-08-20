package com.agentstore.execution.guard

import com.agentstore.execution.repository.ExecutionRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.util.*

@Component
class BudgetGuard(private val executionRepository: ExecutionRepository) {
    @Transactional
    fun reserve(executionId: UUID, amount: BigInteger) {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: error("execution_not_found")
        execution.reserve(amount)
        executionRepository.save(execution)
    }

    @Transactional
    fun settle(executionId: UUID, amount: BigInteger) {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: error("execution_not_found")
        execution.settle(amount)
        executionRepository.save(execution)
    }

    @Transactional
    fun release(executionId: UUID, amount: BigInteger) {
        if (amount.signum() == 0) {
            return
        }
        val execution = executionRepository.findByIdForUpdate(executionId) ?: return
        execution.release(amount)
        executionRepository.save(execution)
    }

    @Transactional
    fun reconcile(executionId: UUID, amount: BigInteger) {
        val execution = executionRepository.findByIdForUpdate(executionId) ?: error("execution_not_found")
        if (execution.reservedCostAtomic.compareTo(amount) >= 0) {
            execution.settle(amount)
            executionRepository.save(execution)
            return
        }
        // Projection is idempotent per PaymentAttempt (`projected_at`), never by the
        // execution aggregate. A missing reservation is therefore unsafe to infer as paid.
        throw IllegalStateException("reconciliation_reservation_missing")
    }
}
