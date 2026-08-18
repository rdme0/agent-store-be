package com.agentstore.execution.dto.response

import com.agentstore.execution.model.entity.Execution
import com.agentstore.execution.model.entity.ExecutionEvent
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.payment.model.entity.PaymentAttempt
import java.time.Instant
import java.util.UUID

data class PaymentAttemptResponse(val id: UUID, val status: String, val amountAtomic: String, val mode: String, val transactionHash: String? = null, val paymentIdentifier: String? = null, val failureCode: String? = null)

data class ExecutionStepResponse(
    val id: UUID,
    val parentStepId: UUID?,
    val agentVersionId: UUID,
    val status: String,
    val costAtomic: String,
    val output: Any? = null,
    val failureCode: String? = null,
    val payments: List<PaymentAttemptResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(step: ExecutionStep, payments: List<PaymentAttempt>) = ExecutionStepResponse(step.id, step.parentStepId, step.agentVersionId, step.status.name, step.costAtomic.toString(), step.output, step.failureCode, payments.map { PaymentAttemptResponse(it.id, it.status.name, it.amountAtomic.toString(), it.paymentMode.name.lowercase(), it.transactionHash, it.paymentIdentifier, it.failureCode) }, step.createdAt, step.updatedAt)
    }
}

data class ExecutionResponse(
    val id: UUID,
    val quoteId: UUID,
    val status: String,
    val maxBudgetAtomic: String,
    val reservedCostAtomic: String,
    val actualCostAtomic: String,
    val question: String? = null,
    val input: Any? = null,
    val failureCode: String? = null,
    val steps: List<ExecutionStepResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ExecutionEventResponse(val id: UUID, val executionId: UUID, val sequence: Int, val type: String, val payload: Any, val createdAt: Instant) {
    companion object {
        fun from(event: ExecutionEvent) = ExecutionEventResponse(event.id, event.executionId, event.sequence, event.type, event.payload, event.createdAt)
    }
}
