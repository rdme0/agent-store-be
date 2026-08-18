package com.agentstore.dependency.controller

import com.agentstore.dependency.dto.CreateDependencyRequest
import com.agentstore.dependency.dto.DependencyResponse
import com.agentstore.dependency.dto.UpdateDependencyRequest
import com.agentstore.dependency.service.DependencyService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class DependencyController(private val service: DependencyService) {
    @GetMapping("/agent-versions/{id}/dependencies")
    fun list(@PathVariable id: UUID): List<DependencyResponse> = service.list(id)

    @PostMapping("/agent-versions/{id}/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable id: UUID, @Valid @RequestBody request: CreateDependencyRequest): DependencyResponse = service.create(id, request)

    @PatchMapping("/agent-versions/{id}/dependencies/{dependencyId}")
    fun update(@PathVariable id: UUID, @PathVariable dependencyId: UUID, @Valid @RequestBody request: UpdateDependencyRequest): DependencyResponse = service.update(id, dependencyId, request)

    @DeleteMapping("/agent-versions/{id}/dependencies/{dependencyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable id: UUID, @PathVariable dependencyId: UUID) = service.remove(id, dependencyId)
}
