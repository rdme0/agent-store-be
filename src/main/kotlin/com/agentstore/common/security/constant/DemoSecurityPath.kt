package com.agentstore.common.security.constant

import jakarta.servlet.http.HttpServletRequest

object DemoSecurityPath {
    fun requiresDemoAccess(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (isDeveloperRead(path)) {
            return true
        }
        if (request.method !in setOf("POST", "PATCH", "PUT", "DELETE")) {
            return false
        }
        return path == "/api/agents" ||
            Regex("/api/agents/[^/]+(?:/versions)?").matches(path) ||
            Regex("/api/agent-versions/[^/]+/(?:publish|verify|disable|verification-input/backfill|manifest)").matches(path) ||
            Regex("/api/agent-versions/[^/]+/dependencies(?:/[^/]+)?").matches(path) ||
            path == "/api/function-contracts" ||
            path == "/api/agent-manifests"
    }

    fun isDeveloperRead(path: String): Boolean {
        return path.startsWith("/api/developer") ||
            Regex("/api/developers/[^/]+/revenue").matches(path) ||
            Regex("/api/agent-versions/[^/]+/(?:readiness|dependencies|manifest)").matches(path) ||
            Regex("/api/agent-manifests/agent-versions/[^/]+").matches(path)
    }
}
