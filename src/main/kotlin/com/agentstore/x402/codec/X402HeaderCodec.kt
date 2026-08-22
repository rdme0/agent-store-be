package com.agentstore.x402.codec

import com.agentstore.x402.dto.internal.X402SettlementReceiptDto
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64

class X402HeaderCodec(private val objectMapper: ObjectMapper) {
    private companion object {
        const val MAX_DECODED_HEADER_LENGTH = 65_536
        const val MAX_ENCODED_HEADER_LENGTH = 87_384
        val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]*={0,2}$")
    }

    fun decodeObject(value: String): ObjectNode {
        require(value.length <= MAX_ENCODED_HEADER_LENGTH) { "x402_header_too_large" }
        require(BASE64_PATTERN.matches(value)) { "invalid_x402_header" }
        val decoded = runCatching { Base64.getDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("invalid_x402_header") }
        require(decoded.size <= MAX_DECODED_HEADER_LENGTH) { "x402_header_too_large" }
        return runCatching { objectMapper.readTree(decoded) }
            .getOrNull()
            ?.takeIf(JsonNode::isObject)
            ?.let { it as ObjectNode }
            ?: throw IllegalArgumentException("invalid_x402_header")
    }

    fun encode(value: ObjectNode): String {
        val bytes = objectMapper.writeValueAsBytes(value)
        require(bytes.size <= MAX_DECODED_HEADER_LENGTH) { "x402_header_too_large" }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decodeReceipt(value: String): X402SettlementReceiptDto {
        val node = decodeObject(value)
        return X402SettlementReceiptDto(
            success = node.path("success").takeIf(JsonNode::isBoolean)?.booleanValue() ?: false,
            transaction = node.path("transaction").takeIf(JsonNode::isTextual)?.textValue(),
            network = node.path("network").takeIf(JsonNode::isTextual)?.textValue(),
        )
    }

}
