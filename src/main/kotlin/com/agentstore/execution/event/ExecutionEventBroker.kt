package com.agentstore.execution.event

import com.agentstore.execution.dto.response.ExecutionEventResponse
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Component
class ExecutionEventBroker {
    private val locks = ConcurrentHashMap<UUID, Any>()
    private val subscriptions = ConcurrentHashMap<UUID, CopyOnWriteArrayList<Subscription>>()
    private val terminalTypes =
        setOf("EXECUTION_COMPLETED", "EXECUTION_FAILED", "EXECUTION_RECONCILIATION_REQUIRED")

    fun subscribe(
        executionId: UUID,
        afterSequence: Int,
        replay: () -> List<ExecutionEventResponse>
    ): SseEmitter {
        val emitter = SseEmitter(0L)
        val lock = locks.computeIfAbsent(executionId) { Any() }
        synchronized(lock) {
            try {
                val cursor = AtomicInteger(afterSequence.coerceAtLeast(0))
                var terminal = false
                replay().forEach { event ->
                    if (event.sequence <= cursor.get()) {
                        return@forEach
                    }
                    emitter.send(toSseEvent(event))
                    cursor.set(event.sequence)
                    terminal = terminal || event.type in terminalTypes
                }
                if (terminal) {
                    emitter.complete()
                } else {
                    val subscription = Subscription(emitter = emitter, cursor = cursor)
                    subscriptions.computeIfAbsent(executionId) { CopyOnWriteArrayList() }
                        .add(subscription)
                    emitter.onCompletion {
                        remove(executionId = executionId, subscription = subscription)
                    }
                    emitter.onTimeout {
                        remove(executionId = executionId, subscription = subscription)
                    }
                    emitter.onError {
                        remove(executionId = executionId, subscription = subscription)
                    }
                }
            } catch (exception: Exception) {
                emitter.completeWithError(exception)
            }
        }
        return emitter
    }

    fun publish(event: ExecutionEventResponse) {
        val lock = locks.computeIfAbsent(event.executionId) { Any() }
        synchronized(lock) {
            val listeners = subscriptions[event.executionId] ?: return
            listeners.forEach { subscription ->
                if (event.sequence <= subscription.cursor.get()) {
                    return@forEach
                }
                try {
                    subscription.emitter.send(toSseEvent(event))
                    subscription.cursor.set(event.sequence)
                    if (event.type in terminalTypes) {
                        listeners.remove(subscription)
                        subscription.emitter.complete()
                    }
                } catch (exception: Exception) {
                    listeners.remove(subscription)
                    subscription.emitter.completeWithError(exception)
                }
            }
            if (listeners.isEmpty()) {
                subscriptions.remove(event.executionId, listeners)
            }
        }
    }

    private fun remove(executionId: UUID, subscription: Subscription) {
        subscriptions[executionId]?.let { listeners ->
            listeners.remove(subscription)
            if (listeners.isEmpty()) {
                subscriptions.remove(executionId, listeners)
            }
        }
    }

    private fun toSseEvent(event: ExecutionEventResponse): SseEmitter.SseEventBuilder {
        return SseEmitter.event()
            .id(event.sequence.toString())
            .name(event.type)
            .data(event.payload)
    }

    private data class Subscription(val emitter: SseEmitter, val cursor: AtomicInteger)
}
