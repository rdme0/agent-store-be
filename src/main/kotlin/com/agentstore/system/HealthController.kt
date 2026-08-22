package com.agentstore.system

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.system.dto.HealthResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "system")
class HealthController(private val properties: AgentStoreProperties) {

    @GetMapping("/health")
    @Operation(operationId = "getHealth", summary = "Health check")
    fun health(): CommonResponse<HealthResponse> {
        return CommonResponse.success(
            HealthResponse(
                status = "ok",
                service = properties.serviceName,
                version = properties.apiVersion,
                timestamp = Instant.now(),
            )
        )
    }
}
