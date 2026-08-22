package com.agentstore.agent.codec

import com.agentstore.agent.dto.internal.AgentListCursorPayloadDto
import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.vo.AgentListSort
import com.agentstore.common.config.AgentStoreProperties
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

@Component
class AgentListCursorCodec(
    private val objectMapper: ObjectMapper,
    properties: AgentStoreProperties,
) {
    private companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    private val signingKey = properties.runtimeTokenSecret.toByteArray(StandardCharsets.UTF_8)

    fun encode(agent: Agent, query: String?, sort: AgentListSort): String {
        val payload = AgentListCursorPayloadDto(
            sort = sort,
            query = query,
            id = agent.id.toString(),
            createdAt = agent.createdAt,
            nameKey = agent.name.lowercase(Locale.ROOT),
        )
        val encodedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(payload))
        val signature = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(sign(value = encodedPayload.toByteArray(StandardCharsets.UTF_8)))

        return "$encodedPayload.$signature"
    }

    fun decode(cursor: String, query: String?, sort: AgentListSort): AgentListCursorPayloadDto {
        val parts = cursor.split('.', limit = 2)
        if (parts.size != 2 || parts.any { value -> value.isBlank() }) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INPUT_VALUE)
        }

        val encodedPayload = parts[0]
        val suppliedSignature = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (_: IllegalArgumentException) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INPUT_VALUE)
        }
        val expectedSignature = sign(value = encodedPayload.toByteArray(StandardCharsets.UTF_8))
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INPUT_VALUE)
        }

        val payload = try {
            objectMapper.readValue(
                Base64.getUrlDecoder().decode(encodedPayload),
                AgentListCursorPayloadDto::class.java,
            )
        } catch (_: Exception) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INPUT_VALUE)
        }
        if (payload.sort != sort || payload.query != query || payload.id.isBlank()) {
            throw DomainClientException(errorCode = ErrorCode.INVALID_INPUT_VALUE)
        }

        when (sort) {
            AgentListSort.NEWEST -> if (payload.createdAt == null) {
                throw DomainClientException(errorCode = ErrorCode.INVALID_INPUT_VALUE)
            }

            AgentListSort.NAME_ASC -> if (payload.nameKey.isNullOrBlank()) {
                throw DomainClientException(errorCode = ErrorCode.INVALID_INPUT_VALUE)
            }
        }

        return payload
    }

    private fun sign(value: ByteArray): ByteArray {
        return Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(signingKey, HMAC_ALGORITHM))
            doFinal(value)
        }
    }

}
