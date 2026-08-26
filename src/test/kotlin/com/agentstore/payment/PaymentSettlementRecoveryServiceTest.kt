package com.agentstore.payment

import com.agentstore.execution.orchestrator.ExecutionPaymentRecoveryProjectionOrchestrator
import com.agentstore.execution.service.ExecutionLifecycleService
import com.agentstore.execution.service.ExecutionStepService
import com.agentstore.payment.client.PaymentReconciliationClient
import com.agentstore.payment.dto.internal.PaymentReconciliationResultDto
import com.agentstore.payment.model.entity.PaymentAttempt
import com.agentstore.payment.model.vo.PaymentReconciliationStatus
import com.agentstore.payment.service.PaymentExternalSettlementService
import com.agentstore.payment.service.PaymentService
import com.agentstore.payment.service.PaymentSettlementRecoveryService
import java.math.BigInteger
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class PaymentSettlementRecoveryServiceTest {
    @Test
    fun `post settlement local failure terminalizes without releasing payment evidence`() {
        val paymentService = mock(PaymentService::class.java)
        val stepService = mock(ExecutionStepService::class.java)
        val lifecycleService = mock(ExecutionLifecycleService::class.java)
        val externalSettlementService = mock(PaymentExternalSettlementService::class.java)
        val reconciliationClient = mock(PaymentReconciliationClient::class.java)
        val projectionOrchestrator =
            mock(ExecutionPaymentRecoveryProjectionOrchestrator::class.java)
        val service = PaymentSettlementRecoveryService(
            paymentService,
            stepService,
            lifecycleService,
            externalSettlementService,
            reconciliationClient,
            projectionOrchestrator
        )
        val executionId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val attempt = PaymentAttempt(
            UUID.randomUUID(),
            stepId,
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0xreceiver"
        )
        attempt.settled("0x${"a".repeat(64)}", "payment-id")

        `when`(paymentService.findSettledAttempts()).thenReturn(listOf(attempt))
        `when`(paymentService.find(attempt.id)).thenReturn(attempt)
        `when`(paymentService.findReconciliationRequiredAttempts()).thenReturn(emptyList())
        `when`(stepService.executionId(stepId)).thenReturn(executionId)
        doThrow(IllegalStateException("local_projection_failed")).`when`(projectionOrchestrator)
            .project(attempt.id)

        service.recoverAll()

        verify(paymentService).markSettlementRecoveryRequired(attempt.id, "FAILED_AFTER_PAYMENT")
        verify(lifecycleService).fail(executionId, stepId, "FAILED_AFTER_PAYMENT")
        verify(paymentService, never()).clearSettlementRecoveryMarker(attempt.id)
    }

    @Test
    fun `unknown x402 reconciliation preserves the reservation and does not create local settlement`() {
        val paymentService = mock(PaymentService::class.java)
        val stepService = mock(ExecutionStepService::class.java)
        val lifecycleService = mock(ExecutionLifecycleService::class.java)
        val externalSettlementService = mock(PaymentExternalSettlementService::class.java)
        val reconciliationClient = mock(PaymentReconciliationClient::class.java)
        val projectionOrchestrator =
            mock(ExecutionPaymentRecoveryProjectionOrchestrator::class.java)
        val service = PaymentSettlementRecoveryService(
            paymentService,
            stepService,
            lifecycleService,
            externalSettlementService,
            reconciliationClient,
            projectionOrchestrator
        )
        val attempt = PaymentAttempt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.ONE,
            "eip155:84532",
            "USDC",
            "0xreceiver"
        )
        attempt.reconciliationRequired("PAYMENT_RECONCILIATION_REQUIRED")
        `when`(paymentService.findSettledAttempts()).thenReturn(emptyList())
        `when`(paymentService.findReconciliationRequiredAttempts()).thenReturn(listOf(attempt))
        `when`(reconciliationClient.reconcile(attempt)).thenReturn(
            PaymentReconciliationResultDto(
                PaymentReconciliationStatus.UNKNOWN
            )
        )

        service.recoverAll()

        verify(externalSettlementService, never()).record(attempt.id, "tx", "payment")
        verifyNoInteractions(projectionOrchestrator)
        verify(paymentService, never()).clearSettlementRecoveryMarker(attempt.id)
    }
}
