package com.agentstore.payment.repository

import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.PaymentAttemptStatus
import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface PaymentAttemptRepository : JpaRepository<PaymentAttempt, UUID> {
    fun findAllByStatusIn(statuses: Collection<PaymentAttemptStatus>): List<PaymentAttempt>
    fun findAllByExecutionStepIdOrderByCreatedAtAsc(executionStepId: UUID): List<PaymentAttempt>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentAttempt p where p.id = :id")
    fun findByIdForUpdate(id: UUID): PaymentAttempt?
}
