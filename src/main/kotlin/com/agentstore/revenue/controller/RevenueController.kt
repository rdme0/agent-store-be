package com.agentstore.revenue.controller

import com.agentstore.revenue.dto.DeveloperRevenueResponse
import com.agentstore.revenue.service.RevenueService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/developers")
class RevenueController(private val service: RevenueService) {
    @GetMapping("/{id}/revenue")
    fun get(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: UUID?,
        @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): DeveloperRevenueResponse = service.get(id, cursor, limit)
}
