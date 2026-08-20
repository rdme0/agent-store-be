package com.agentstore.dependency.repository

import com.agentstore.dependency.model.entity.ExecutionQuote
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface ExecutionQuoteRepository : JpaRepository<ExecutionQuote, UUID>
