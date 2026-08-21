package com.agentstore.execution.dto.response

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.execution.model.entity.ExecutionEvent
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.payment.model.entity.PaymentAttempt
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.*

data class PaymentAttemptResponse(
    val id: UUID,
    @field:Schema(allowableValues = ["REQUIRED", "AUTHORIZED", "SETTLED", "FAILED", "RECONCILIATION_REQUIRED"]) val status: String,
    @field:Schema(pattern = "^[0-9]+$") val amountAtomic: String,
    @field:Schema(allowableValues = ["simulated", "x402"]) val mode: String,
    @field:Schema(nullable = false) val transactionHash: String? = null,
    @field:Schema(nullable = false) val paymentIdentifier: String? = null,
    @field:Schema(nullable = false) val failureCode: String? = null
)

data class ExecutionStepResponse(
    val id: UUID,
    val parentStepId: UUID?,
    val agentVersionId: UUID,
    @field:Schema(allowableValues = ["CREATED", "PAYMENT_REQUIRED", "PAYMENT_SETTLED", "RUNNING", "COMPLETED", "FAILED"]) val status: String,
    @field:Schema(pattern = "^[0-9]+$") val costAtomic: String,
    @field:Schema(allowableValues = ["TEXT", "MARKDOWN", "STRUCTURED", "JSON"])
    val responseFormat: AgentResponseFormat = AgentResponseFormat.JSON,
    @field:Schema(implementation = JsonNode::class, nullable = false) val output: Any? = null,
    @field:Schema(nullable = false) val failureCode: String? = null,
    val payments: List<PaymentAttemptResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(
            step: ExecutionStep,
            payments: List<PaymentAttempt>,
            output: Any? = step.output,
            responseFormat: AgentResponseFormat = AgentResponseFormat.JSON,
        ): ExecutionStepResponse {
            return ExecutionStepResponse(
                step.id,
                step.parentStepId,
                step.agentVersionId,
                step.status.name,
                step.costAtomic.toString(),
                responseFormat,
                output,
                step.failureCode,
                payments.map {
                    PaymentAttemptResponse(
                        it.id,
                        it.status.name,
                        it.amountAtomic.toString(),
                        it.paymentMode.name.lowercase(),
                        it.transactionHash,
                        it.paymentIdentifier,
                        it.failureCode
                    )
                },
                step.createdAt,
                step.updatedAt,
            )
        }
    }
}

data class ExecutionResponse(
    val id: UUID,
    val quoteId: UUID,
    @field:Schema(allowableValues = ["PENDING", "RUNNING", "COMPLETED", "FAILED"]) val status: String,
    @field:Schema(pattern = "^[0-9]+$") val maxBudgetAtomic: String,
    @field:Schema(pattern = "^[0-9]+$") val reservedCostAtomic: String,
    @field:Schema(pattern = "^[0-9]+$") val actualCostAtomic: String,
    @field:Schema(nullable = false) val question: String? = null,
    @field:Schema(implementation = JsonNode::class, nullable = false) val input: Any? = null,
    @field:Schema(nullable = false) val failureCode: String? = null,
    val steps: List<ExecutionStepResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ExecutionEventResponse(
    val id: UUID,
    val executionId: UUID,
    val sequence: Int,
    val type: String,
    val payload: Any,
    val createdAt: Instant
) {
    companion object {
        fun from(event: ExecutionEvent, payload: Any = event.payload): ExecutionEventResponse {
            return ExecutionEventResponse(
                event.id,
                event.executionId,
                event.sequence,
                event.type,
                payload,
                event.createdAt
            )
        }
    }
}
