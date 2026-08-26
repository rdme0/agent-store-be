package com.agentstore.payment.service

import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.entity.PaymentSettlementJournal
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import com.agentstore.payment.repository.PaymentAttemptRepository
import com.agentstore.payment.repository.PaymentSettlementJournalRepository
import jakarta.transaction.Transactional
import java.math.BigInteger
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class PaymentService(
    private val attemptRepository: PaymentAttemptRepository,
    private val journalRepository: PaymentSettlementJournalRepository,
) {
    /**
     * Only a durable settlement journal/SETTLED status proves spend.
     * UNKNOWN attempts wait for native reconciliation.
     */
    fun findSettledAttempts(): List<PaymentAttempt> {
        return attemptRepository.findAllByStatusIn(listOf(PaymentAttemptStatus.SETTLED))
    }

    fun findReconciliationRequiredAttempts(): List<PaymentAttempt> {
        return attemptRepository.findAllByStatusIn(listOf(PaymentAttemptStatus.RECONCILIATION_REQUIRED))
    }

    fun findRequiredAttempts(): List<PaymentAttempt> {
        return attemptRepository.findAllByStatusIn(listOf(PaymentAttemptStatus.REQUIRED))
    }

    @Transactional
    fun markProjected(attemptId: UUID) {
        val attempt = attemptRepository.findByIdForUpdate(attemptId) ?: return
        attempt.markProjected()
        attemptRepository.save(attempt)
    }

    fun find(attemptId: UUID): PaymentAttempt {
        return attemptRepository.findById(attemptId)
            .orElseThrow { IllegalStateException("payment_attempt_not_found") }
    }

    @Transactional
    fun findForUpdate(attemptId: UUID): PaymentAttempt {
        return attemptRepository.findByIdForUpdate(attemptId) ?: error("payment_attempt_not_found")
    }

    fun findAllByStepId(stepId: UUID): List<PaymentAttempt> {
        return attemptRepository.findAllByExecutionStepIdOrderByCreatedAtAsc(stepId)
    }

    @Transactional
    fun require(
        stepId: UUID,
        amount: BigInteger,
        network: String,
        asset: String,
        payTo: String,
    ): UUID {
        val attempt = PaymentAttempt(UUID.randomUUID(), stepId, amount, network, asset, payTo)
        return attemptRepository.save(attempt).id
    }

    @Transactional
    fun settle(attemptId: UUID, transactionHash: String, paymentIdentifier: String?): BigInteger {
        val attempt =
            attemptRepository.findByIdForUpdate(attemptId) ?: error("payment_attempt_not_found")
        val journal = journalRepository.findByPaymentAttemptId(attempt.id)
        if (attempt.status == PaymentAttemptStatus.SETTLED) {
            if (journal?.transactionHash != transactionHash || attempt.transactionHash != transactionHash) {
                throw IllegalStateException("settlement_hash_mismatch")
            }
            return attempt.amountAtomic
        }
        if (journal == null) {
            journalRepository.save(
                PaymentSettlementJournal(
                    UUID.randomUUID(),
                    attempt.id,
                    transactionHash
                )
            )
        } else if (journal.transactionHash != transactionHash) {
            throw IllegalStateException("settlement_hash_mismatch")
        }
        attempt.settled(transactionHash, paymentIdentifier)
        attemptRepository.save(attempt)
        return attempt.amountAtomic
    }

    @Transactional
    fun markReconciliationRequired(attemptId: UUID, failureCode: String) {
        val attempt = attemptRepository.findByIdForUpdate(attemptId) ?: return
        if (attempt.status == PaymentAttemptStatus.SETTLED) {
            return
        }
        attempt.reconciliationRequired(failureCode)
        attemptRepository.save(attempt)
    }

    @Transactional
    fun markSettlementRecoveryRequired(attemptId: UUID, failureCode: String) {
        val attempt = attemptRepository.findByIdForUpdate(attemptId) ?: return
        attempt.markSettlementRecoveryRequired(failureCode)
        attemptRepository.save(attempt)
    }

    @Transactional
    fun clearSettlementRecoveryMarker(attemptId: UUID) {
        val attempt = attemptRepository.findByIdForUpdate(attemptId) ?: return
        attempt.clearSettlementRecoveryMarker()
        attemptRepository.save(attempt)
    }

    @Transactional
    fun fail(attemptId: UUID, failureCode: String) {
        val attempt = attemptRepository.findByIdForUpdate(attemptId) ?: return
        attempt.failed(failureCode)
        attemptRepository.save(attempt)
    }
}
