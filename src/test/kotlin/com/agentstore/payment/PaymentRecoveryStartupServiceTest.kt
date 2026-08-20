package com.agentstore.payment

import com.agentstore.common.exception.ApiException
import com.agentstore.execution.guard.ExecutionMutationReadiness
import com.agentstore.execution.orchestrator.ExecutionPaymentRecoveryOrchestrator
import com.agentstore.execution.service.ExecutionRecoveryService
import com.agentstore.payment.config.PaymentRecoveryStartupService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock

class PaymentRecoveryStartupServiceTest {
    @Test
    fun `startup failure keeps execution mutations unavailable`() {
        val recovery = mock(ExecutionPaymentRecoveryOrchestrator::class.java)
        val executionRecovery = mock(ExecutionRecoveryService::class.java)
        val readiness = ExecutionMutationReadiness()
        doThrow(IllegalStateException("database_unavailable")).`when`(recovery).reconcileSettledPayments()

        assertThatThrownBy { PaymentRecoveryStartupService(recovery, executionRecovery, readiness).reconcile() }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { readiness.requireReady() }.isInstanceOf(ApiException::class.java)
    }
}
