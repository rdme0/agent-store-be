package com.agentstore.system

import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.dto.response.CommonResponse
import com.agentstore.system.dto.HealthResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@Tag(name = "system")
class HealthController(private val properties: AgentStoreProperties) {

    @GetMapping("/health")
    @Operation(operationId = "getHealth", summary = "Health check")
    fun health(): CommonResponse<HealthResponse> {
        return CommonResponse.success(HealthResponse("ok", properties.serviceName, properties.apiVersion, Instant.now()))
    }
}
