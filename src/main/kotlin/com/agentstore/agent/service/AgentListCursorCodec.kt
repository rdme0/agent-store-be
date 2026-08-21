package com.agentstore.agent.service

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.vo.AgentListSort
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class AgentListCursorCodec(
    private val objectMapper: ObjectMapper,
    properties: AgentStoreProperties,
) {
    private val signingKey = properties.runtimeTokenSecret.toByteArray(StandardCharsets.UTF_8)

    fun encode(agent: Agent, query: String?, sort: AgentListSort): String {
        val payload = AgentListCursorPayload(
            sort = sort,
            query = query,
            id = agent.id.toString(),
            createdAt = agent.createdAt,
            nameKey = agent.name.lowercase(Locale.ROOT),
        )
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(payload))
        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sign(encodedPayload.toByteArray(StandardCharsets.UTF_8)))
        return "$encodedPayload.$signature"
    }

    fun decode(cursor: String, query: String?, sort: AgentListSort): AgentListCursorPayload {
        val parts = cursor.split('.', limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            throw invalidCursor()
        }
        val encodedPayload = parts[0]
        val suppliedSignature = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (_: IllegalArgumentException) {
            throw invalidCursor()
        }
        if (!MessageDigest.isEqual(sign(encodedPayload.toByteArray(StandardCharsets.UTF_8)), suppliedSignature)) {
            throw invalidCursor()
        }
        val payload = try {
            objectMapper.readValue(
                Base64.getUrlDecoder().decode(encodedPayload),
                AgentListCursorPayload::class.java,
            )
        } catch (_: Exception) {
            throw invalidCursor()
        }
        if (payload.sort != sort || payload.query != query || payload.id.isBlank()) {
            throw invalidCursor()
        }
        when (sort) {
            AgentListSort.NEWEST -> if (payload.createdAt == null) throw invalidCursor()
            AgentListSort.NAME_ASC -> if (payload.nameKey.isNullOrBlank()) throw invalidCursor()
        }
        return payload
    }

    private fun sign(value: ByteArray): ByteArray {
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
            doFinal(value)
        }
    }

    private fun invalidCursor(): DomainClientException {
        return DomainClientException(ErrorCode.INVALID_INPUT_VALUE)
    }
}

data class AgentListCursorPayload(
    val sort: AgentListSort,
    val query: String?,
    val id: String,
    val createdAt: Instant? = null,
    val nameKey: String? = null,
)
