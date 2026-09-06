package com.agentstore.external.repository

import com.agentstore.external.model.entity.ExternalInvocation
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ExternalInvocationRepository : JpaRepository<ExternalInvocation, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): ExternalInvocation?

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:idempotencyKey))", nativeQuery = true)
    fun acquireIdempotencyLock(idempotencyKey: String): Int

}
