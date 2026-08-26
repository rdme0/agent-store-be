package com.agentstore.execution.dto.response

import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.dependency.dto.internal.QuoteSnapshotDto
import com.agentstore.execution.model.entity.ExecutionEvent
import com.agentstore.execution.model.entity.ExecutionStep
import com.agentstore.payment.dto.response.KrwEstimateResponse
import com.agentstore.payment.model.entity.PaymentAttempt
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class PaymentAttemptResponse(
    val id: UUID,
    @field:Schema(
        allowableValues = ["REQUIRED", "AUTHORIZED", "SETTLED", "FAILED", "RECONCILIATION_REQUIRED"],
    )
    val status: String,
    @field:Schema(pattern = "^[0-9]+$") val amountAtomic: String,
    @field:Schema(nullable = false) val transactionHash: String? = null,
    @field:Schema(nullable = false) val paymentIdentifier: String? = null,
    @field:Schema(nullable = false) val failureCode: String? = null
)

data class ExecutionStepResponse(
    val id: UUID,
    val parentStepId: UUID?,
    val agentVersionId: UUID,
    val agentCode: String? = null,
    val agentName: String? = null,
    @field:Schema(
        allowableValues = ["CREATED", "PAYMENT_REQUIRED", "PAYMENT_SETTLED", "RUNNING", "COMPLETED", "FAILED"],
    )
    val status: String,
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
            agentCode: String? = null,
            agentName: String? = null,
        ): ExecutionStepResponse {
            return ExecutionStepResponse(
                id = step.id,
                parentStepId = step.parentStepId,
                agentVersionId = step.agentVersionId,
                agentCode = agentCode,
                agentName = agentName,
                status = step.status.name,
                costAtomic = step.costAtomic.toString(),
                responseFormat = responseFormat,
                output = output,
                failureCode = step.failureCode,
                payments = payments.map { payment ->
                    PaymentAttemptResponse(
                        id = payment.id,
                        status = payment.status.name,
                        amountAtomic = payment.amountAtomic.toString(),
                        transactionHash = payment.transactionHash,
                        paymentIdentifier = payment.paymentIdentifier,
                        failureCode = payment.failureCode,
                    )
                },
                createdAt = step.createdAt,
                updatedAt = step.updatedAt,
            )
        }
    }
}

data class ExecutionResponse(
    val id: UUID,
    val quoteId: UUID,
    val quoteSnapshot: QuoteSnapshotDto,
    @field:Schema(allowableValues = ["PENDING", "RUNNING", "COMPLETED", "FAILED"]) val status: String,
    @field:Schema(pattern = "^[0-9]+$") val maxBudgetAtomic: String,
    val maxBudgetKrwEstimate: KrwEstimateResponse? = null,
    @field:Schema(pattern = "^[0-9]+$") val reservedCostAtomic: String,
    @field:Schema(pattern = "^[0-9]+$") val actualCostAtomic: String,
    val actualCostKrwEstimate: KrwEstimateResponse? = null,
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
                id = event.id,
                executionId = event.executionId,
                sequence = event.sequence,
                type = event.type,
                payload = payload,
                createdAt = event.createdAt,
            )
        }
    }
}
