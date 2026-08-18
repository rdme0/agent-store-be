package com.agentstore.agent.controller

import com.agentstore.agent.dto.request.CreateAgentRequest
import com.agentstore.agent.dto.request.CreateAgentVersionRequest
import com.agentstore.agent.dto.request.UpdateAgentRequest
import com.agentstore.agent.dto.response.AgentListResponse
import com.agentstore.agent.dto.response.AgentResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.service.AgentService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class AgentController(private val service: AgentService) {
    @GetMapping("/agents")
    fun list(
        @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(50) limit: Int,
        @RequestParam(required = false) cursor: UUID?,
    ): AgentListResponse = service.list(limit, cursor)

    @GetMapping("/agents/{slug}")
    fun getBySlug(@PathVariable slug: String): AgentResponse = service.getBySlug(slug)

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateAgentRequest): AgentResponse = service.create(request)

    @PatchMapping("/agents/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateAgentRequest): AgentResponse = service.update(id, request)

    @DeleteMapping("/agents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)

    @PostMapping("/agents/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    fun createVersion(@PathVariable id: UUID, @Valid @RequestBody request: CreateAgentVersionRequest): AgentVersionResponse = service.createVersion(id, request)

    @PostMapping("/agent-versions/{id}/publish")
    fun publish(@PathVariable id: UUID): AgentVersionResponse = service.publish(id)

    @PostMapping("/agent-versions/{id}/disable")
    fun disable(@PathVariable id: UUID): AgentVersionResponse = service.disable(id)
}
