package com.agentstore.developer.service

import com.agentstore.agent.config.DevIdentityInitializer
import com.agentstore.common.security.helper.DemoAccessTokenHelper
import com.agentstore.developer.dto.response.DemoAccessResponse
import org.springframework.stereotype.Service

@Service
class DemoAccessService(
    private val tokenHelper: DemoAccessTokenHelper,
) {
    fun issue(): DemoAccessResponse {
        val issued = tokenHelper.issue(developerId = DevIdentityInitializer.DEMO_DEVELOPER_ID)
        return DemoAccessResponse(accessToken = issued.accessToken, expiresAt = issued.expiresAt)
    }
}
