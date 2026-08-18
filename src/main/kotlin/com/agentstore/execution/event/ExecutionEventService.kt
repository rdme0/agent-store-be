package com.agentstore.execution.event

import com.agentstore.execution.dto.response.ExecutionEventResponse
import com.agentstore.execution.model.entity.ExecutionEvent
import com.agentstore.execution.repository.ExecutionEventRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ExecutionEventService(
    private val repository: ExecutionEventRepository,
    private val objectMapper: ObjectMapper,
) {
    fun append(executionId: UUID, type: String, payload: Any): ExecutionEventResponse {
        val sequence = (repository.findFirstByExecutionIdOrderBySequenceDesc(executionId)?.sequence ?: 0) + 1
        val event = repository.save(ExecutionEvent(UUID.randomUUID(), executionId, sequence, type, objectMapper.valueToTree<JsonNode>(payload)))
        return ExecutionEventResponse.from(event)
    }

    fun replay(executionId: UUID, afterSequence: Int): List<ExecutionEventResponse> = repository.findReplay(executionId, afterSequence).map(ExecutionEventResponse::from)
}
