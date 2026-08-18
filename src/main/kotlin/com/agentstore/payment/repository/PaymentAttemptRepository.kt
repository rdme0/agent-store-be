package com.agentstore.payment.repository

import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentAttemptRepository : JpaRepository<PaymentAttempt, UUID> {
    fun findAllByStatusIn(statuses: Collection<PaymentAttemptStatus>): List<PaymentAttempt>
}
