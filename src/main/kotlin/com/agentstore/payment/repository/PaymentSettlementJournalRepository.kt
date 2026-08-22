package com.agentstore.payment.repository

import com.agentstore.payment.model.entity.PaymentSettlementJournal
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentSettlementJournalRepository : JpaRepository<PaymentSettlementJournal, UUID> {
    fun findByPaymentAttemptId(paymentAttemptId: UUID): PaymentSettlementJournal?
}
