package com.agentstore.execution.controller

import com.agentstore.execution.dto.request.CreateExecutionRequest
import com.agentstore.execution.dto.response.ExecutionResponse
import com.agentstore.execution.service.ExecutionService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@RestController
@RequestMapping("/api/executions")
class ExecutionController(private val service: ExecutionService) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreateExecutionRequest): ResponseEntity<ExecutionResponse> = ResponseEntity.accepted().body(service.create(request))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ExecutionResponse = service.get(id)

    @GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@PathVariable id: UUID, @RequestHeader("Last-Event-ID", required = false) lastEventId: String?): SseEmitter {
        val after = lastEventId?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val emitter = SseEmitter(0L)
        runCatching {
            service.events(id, after).forEach { event ->
                emitter.send(SseEmitter.event().id(event.sequence.toString()).name(event.type).data(event.payload))
            }
        }.onFailure { emitter.completeWithError(it) }
        return emitter
    }
}
