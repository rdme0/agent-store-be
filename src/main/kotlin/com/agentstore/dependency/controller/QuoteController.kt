package com.agentstore.dependency.controller

import com.agentstore.dependency.dto.request.QuoteRequest
import com.agentstore.dependency.dto.response.QuoteResponse
import com.agentstore.dependency.service.QuoteService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agents")
class QuoteController(private val service: QuoteService) {
    @PostMapping("/{slug}/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable slug: String, @Valid @RequestBody(required = false) request: QuoteRequest?): QuoteResponse = service.create(slug, request ?: QuoteRequest())
}
