package com.agentstore.payment.service

import com.agentstore.payment.model.entity.PaymentSettlementJournal
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import com.agentstore.payment.repository.PaymentAttemptRepository
import com.agentstore.payment.repository.PaymentSettlementJournalRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.util.*

/** Commits the external payment journal before any local execution projection can fail. */
@Service
class PaymentExternalSettlementService(
    private val attemptRepository: PaymentAttemptRepository,
    private val journalRepository: PaymentSettlementJournalRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(attemptId: UUID, transactionHash: String, paymentIdentifier: String?): BigInteger {
        val attempt = attemptRepository.findByIdForUpdate(attemptId) ?: error("payment_attempt_not_found")
        val journal = journalRepository.findByPaymentAttemptId(attempt.id)
        if (attempt.status == PaymentAttemptStatus.SETTLED) {
            if (journal?.transactionHash != transactionHash || attempt.transactionHash != transactionHash) {
                error("settlement_hash_mismatch")
            }
            return attempt.amountAtomic
        }
        if (journal == null) {
            journalRepository.save(PaymentSettlementJournal(UUID.randomUUID(), attempt.id, transactionHash))
        } else if (journal.transactionHash != transactionHash) {
            error("settlement_hash_mismatch")
        }
        attempt.settled(transactionHash, paymentIdentifier)
        attemptRepository.save(attempt)
        return attempt.amountAtomic
    }
}
