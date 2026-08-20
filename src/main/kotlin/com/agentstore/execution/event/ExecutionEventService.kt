package com.agentstore.execution.event

import com.agentstore.execution.dto.response.ExecutionEventResponse
import com.agentstore.execution.model.entity.ExecutionEvent
import com.agentstore.execution.repository.ExecutionEventRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Component
class ExecutionEventService(
    private val repository: ExecutionEventRepository,
    private val objectMapper: ObjectMapper,
    private val broker: ExecutionEventBroker,
) {
    fun append(executionId: UUID, type: String, payload: Any): ExecutionEventResponse {
        val lock = sequenceLocks.computeIfAbsent(executionId) { Any() }
        synchronized(lock) {
            val sequence = (repository.findFirstByExecutionIdOrderBySequenceDesc(executionId)?.sequence ?: 0) + 1
            val event = repository.save(
                ExecutionEvent(
                    UUID.randomUUID(),
                    executionId,
                    sequence,
                    type,
                    objectMapper.valueToTree<JsonNode>(payload)
                )
            )
            val response = ExecutionEventResponse.from(event, jsonValue(event.payload))
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        broker.publish(response)
                    }
                })
            } else {
                broker.publish(response)
            }
            return response
        }
    }

    fun replay(executionId: UUID, afterSequence: Int): List<ExecutionEventResponse> {
        return repository.findReplay(executionId, afterSequence).map { event ->
            ExecutionEventResponse.from(event, jsonValue(event.payload))
        }
    }

    fun subscribe(executionId: UUID, afterSequence: Int): SseEmitter {
        return broker.subscribe(executionId, afterSequence) { replay(executionId, afterSequence) }
    }

    private fun jsonValue(value: JsonNode): Any {
        return objectMapper.convertValue(value, Any::class.java)
    }

    private companion object {
        val sequenceLocks = ConcurrentHashMap<UUID, Any>()
    }
}
