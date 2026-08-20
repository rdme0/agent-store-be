package com.agentstore.common.config

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object DatabaseUrlParser {
    fun parse(value: String): JdbcDatabaseProperties {
        require(value.isNotBlank()) { "Database URL must not be blank" }
        if (value.startsWith("jdbc:postgresql://")) {
            val uri = URI(value.removePrefix("jdbc:"))
            return JdbcDatabaseProperties(
                value,
                decode(uri.userInfo?.substringBefore(':').orEmpty()),
                decode(uri.userInfo?.substringAfter(':', "").orEmpty())
            )
        }
        require(value.startsWith("postgresql://") || value.startsWith("postgres://")) {
            "Database URL must be a PostgreSQL URL"
        }
        val uri = URI(value)
        val userInfo = uri.userInfo ?: error("Database URL must include a username")
        val username = decode(userInfo.substringBefore(':'))
        val password = decode(userInfo.substringAfter(':', ""))
        require(username.isNotBlank()) { "Database URL must include a username" }
        val database = uri.path.removePrefix("/").takeIf { it.isNotBlank() }
            ?: error("Database URL must include a database name")
        val query = uri.rawQuery.orEmpty()
        val jdbcQuery = query.replace("schema=", "currentSchema=")
        val jdbcUrl = buildString {
            append("jdbc:postgresql://")
            append(uri.host ?: error("Database URL must include a host"))
            if (uri.port != -1) {
                append(':').append(uri.port)
            }
            append('/').append(database)
            if (jdbcQuery.isNotBlank()) {
                append('?').append(jdbcQuery)
            }
        }
        return JdbcDatabaseProperties(jdbcUrl, username, password)
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
