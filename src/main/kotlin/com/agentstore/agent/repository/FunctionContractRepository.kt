package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.FunctionContract
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface FunctionContractRepository : JpaRepository<FunctionContract, UUID> {
    fun findByCodeAndContractVersion(code: String, contractVersion: String): FunctionContract?
    fun findAllByOrderByCodeAscContractVersionAsc(): List<FunctionContract>
}
