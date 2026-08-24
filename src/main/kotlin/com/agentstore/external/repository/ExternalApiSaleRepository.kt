package com.agentstore.external.repository

import com.agentstore.external.model.entity.ExternalApiSale
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ExternalApiSaleRepository : JpaRepository<ExternalApiSale, UUID> {
    fun existsByExternalIntentId(externalIntentId: UUID): Boolean
}
