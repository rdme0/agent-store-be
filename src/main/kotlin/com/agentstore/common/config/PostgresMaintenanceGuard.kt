package com.agentstore.common.config

import org.springframework.core.env.Environment
import java.net.InetSocketAddress
import java.net.Socket

/** Fails before a DataSource/Flyway connection is created when exclusive maintenance was not established. */
class PostgresMaintenanceGuard(
    private val isLocalPortOpen: (Int) -> Boolean = ::isLocalPortOpen,
) {
    fun verify(environment: Environment, properties: AgentStoreProperties) {
        require(properties.integrationTestsEnabled) {
            "Spring database startup requires RUN_POSTGRES_INTEGRATION_TESTS=true"
        }
        require(properties.exclusiveMaintenanceEnabled) {
            "Spring database startup requires SPRING_EXCLUSIVE_MAINTENANCE=true"
        }
        check(!isLocalPortOpen(8080)) {
            "Local port 8080 is listening; stop the TypeScript API before Spring maintenance"
        }
    }

    private companion object {
        fun isLocalPortOpen(port: Int): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
