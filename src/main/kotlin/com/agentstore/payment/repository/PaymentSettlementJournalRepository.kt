package com.agentstore.payment.repository

import com.agentstore.payment.model.entity.PaymentSettlementJournal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentSettlementJournalRepository : JpaRepository<PaymentSettlementJournal, UUID>
