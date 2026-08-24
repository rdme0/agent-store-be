package com.agentstore.external.repository

import com.agentstore.external.model.entity.ExternalInvocationIntent
import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ExternalInvocationIntentRepository : JpaRepository<ExternalInvocationIntent, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): ExternalInvocationIntent?

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:idempotencyKey))", nativeQuery = true)
    fun acquireIdempotencyLock(idempotencyKey: String): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from ExternalInvocationIntent intent where intent.id = :id")
    fun findByIdForUpdate(id: UUID): ExternalInvocationIntent?
}
