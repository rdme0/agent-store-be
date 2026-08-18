package com.agentstore.payment.config

import com.agentstore.execution.orchestrator.ExecutionPaymentRecoveryOrchestrator
import com.agentstore.execution.service.ExecutionRecoveryService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class PaymentRecoveryStartupService(
    private val recovery: ExecutionPaymentRecoveryOrchestrator,
    private val executionRecoveryService: ExecutionRecoveryService,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun reconcile() {
        recovery.reconcileSettledPayments()
        executionRecoveryService.failActiveExecutions()
    }
}
