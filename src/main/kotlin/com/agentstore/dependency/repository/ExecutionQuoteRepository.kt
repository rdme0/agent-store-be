package com.agentstore.dependency.repository

import com.agentstore.dependency.model.entity.ExecutionQuote
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ExecutionQuoteRepository : JpaRepository<ExecutionQuote, UUID>
