package com.agentstore.execution.event

import com.agentstore.execution.dto.response.ExecutionEventResponse
import com.agentstore.execution.model.entity.ExecutionEvent
import com.agentstore.execution.repository.ExecutionEventRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class ExecutionEventService(
    private val repository: ExecutionEventRepository,
    private val objectMapper: ObjectMapper,
    private val broker: ExecutionEventBroker,
) {
    fun append(executionId: UUID, type: String, payload: Any): ExecutionEventResponse {
        val lock = sequenceLocks.computeIfAbsent(executionId) { Any() }
        synchronized(lock) {
            val sequence = (repository.findFirstByExecutionIdOrderBySequenceDesc(executionId)?.sequence ?: 0) + 1
            val event = repository.save(ExecutionEvent(UUID.randomUUID(), executionId, sequence, type, objectMapper.valueToTree<JsonNode>(payload)))
            val response = ExecutionEventResponse.from(event)
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() { broker.publish(response) }
                })
            } else {
                broker.publish(response)
            }
            return response
        }
    }

    fun replay(executionId: UUID, afterSequence: Int): List<ExecutionEventResponse> = repository.findReplay(executionId, afterSequence).map(ExecutionEventResponse::from)

    fun subscribe(executionId: UUID, afterSequence: Int): SseEmitter = broker.subscribe(executionId, afterSequence) { replay(executionId, afterSequence) }

    private companion object {
        val sequenceLocks = ConcurrentHashMap<UUID, Any>()
    }
}
