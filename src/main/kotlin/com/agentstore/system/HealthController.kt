package com.agentstore.system

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "system")
class HealthController {
    @GetMapping("/health")
    @Operation(summary = "Health check")
    fun health(): Map<String, String> = mapOf("status" to "ok")
}
