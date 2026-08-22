package com.agentstore.x402.registry

import com.agentstore.payment.dto.internal.PaymentInvocationResultDto
import com.agentstore.payment.dto.internal.PaymentReconciliationResultDto
import com.agentstore.payment.model.vo.PaymentReconciliationStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

class X402PaymentCorrelationRegistry {
    private companion object {
        val UNKNOWN_RESULT = PaymentReconciliationResultDto(status = PaymentReconciliationStatus.UNKNOWN)
    }

    private data class Entry(
        val fingerprint: String,
        val result: CompletableFuture<PaymentInvocationResultDto>,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun claim(
        paymentAttemptId: String,
        idempotencyKey: String,
        fingerprint: String,
        execute: () -> PaymentInvocationResultDto,
    ): PaymentInvocationResultDto {
        val key = key(paymentAttemptId = paymentAttemptId, idempotencyKey = idempotencyKey)
        val candidate = Entry(
            fingerprint = fingerprint,
            result = CompletableFuture(),
        )
        val entry = entries.putIfAbsent(key, candidate) ?: candidate
        require(entry.fingerprint == fingerprint) { "payment_attempt_fingerprint_mismatch" }
        if (entry === candidate) {
            runCatching(execute).fold(
                onSuccess = entry.result::complete,
                onFailure = entry.result::completeExceptionally,
            )
        }
        return try {
            entry.result.join()
        } catch (exception: CompletionException) {
            throw (exception.cause as? RuntimeException ?: exception)
        }
    }

    fun reconcile(paymentAttemptId: String, idempotencyKey: String): PaymentReconciliationResultDto {
        val future = entries[key(paymentAttemptId = paymentAttemptId, idempotencyKey = idempotencyKey)]?.result
            ?: return UNKNOWN_RESULT
        if (!future.isDone || future.isCompletedExceptionally) {
            return UNKNOWN_RESULT
        }
        val result = runCatching { future.getNow(null) }.getOrNull() ?: return UNKNOWN_RESULT
        val transactionHash = result.transactionHash?.takeIf(String::isNotBlank) ?: return UNKNOWN_RESULT
        return PaymentReconciliationResultDto(
            status = PaymentReconciliationStatus.SETTLED,
            transactionHash = transactionHash,
            paymentIdentifier = result.paymentIdentifier,
        )
    }

    private fun key(paymentAttemptId: String, idempotencyKey: String): String {
        return "$paymentAttemptId\u0000$idempotencyKey"
    }

}
