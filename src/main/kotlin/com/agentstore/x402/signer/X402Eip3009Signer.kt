package com.agentstore.x402.signer

import com.agentstore.x402.dto.internal.X402AuthorizationDto
import com.agentstore.x402.dto.internal.X402PaymentRequiredDto
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.security.SecureRandom
import java.time.Clock
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import org.web3j.utils.Numeric

class X402Eip3009Signer(
    privateKey: String,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val secureRandom: SecureRandom,
) {
    private companion object {
        const val CHAIN_ID = 84_532L
        val PRIVATE_KEY_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")
        val SECP256K1_N =
            BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    }

    private val credentials: Credentials

    init {
        require(PRIVATE_KEY_PATTERN.matches(privateKey)) {
            "X402_PRIVATE_KEY must be 0x followed by 64 hex characters"
        }
        val privateKeyValue = Numeric.toBigInt(privateKey)
        require(privateKeyValue.signum() > 0 && privateKeyValue < SECP256K1_N) {
            "X402_PRIVATE_KEY is invalid"
        }
        credentials = runCatching { Credentials.create(privateKey) }
            .getOrElse { throw IllegalArgumentException("X402_PRIVATE_KEY is invalid") }
    }

    fun createPaymentPayload(required: X402PaymentRequiredDto): ObjectNode {
        val nonceBytes = ByteArray(32).also(secureRandom::nextBytes)
        val authorization = X402AuthorizationDto(
            from = credentials.address,
            to = required.selected.path("payTo").textValue(),
            value = required.selected.path("amount").textValue(),
            validAfter = "0",
            validBefore = Math.addExact(clock.instant().epochSecond, required.maxTimeoutSeconds)
                .toString(),
            nonce = Numeric.toHexString(nonceBytes),
        )
        val signature = sign(authorization = authorization, required = required)
        return objectMapper.createObjectNode().apply {
            put("x402Version", 2)
            set<ObjectNode>("resource", required.resource.deepCopy())
            set<ObjectNode>("accepted", required.selected.deepCopy())
            required.extensions?.let { set<ObjectNode>("extensions", it.deepCopy()) }
            set<ObjectNode>("payload", objectMapper.createObjectNode().apply {
                set<ObjectNode>("authorization", objectMapper.valueToTree(authorization))
                put("signature", signature)
            })
        }
    }

    private fun sign(authorization: X402AuthorizationDto, required: X402PaymentRequiredDto): String {
        val typedData = objectMapper.createObjectNode().apply {
            set<ObjectNode>("types", objectMapper.createObjectNode().apply {
                set<ArrayNode>(
                    "EIP712Domain",
                    fields(
                        values = arrayOf(
                            "name" to "string",
                            "version" to "string",
                            "chainId" to "uint256",
                            "verifyingContract" to "address",
                        ),
                    ),
                )
                set<ArrayNode>(
                    "TransferWithAuthorization",
                    fields(
                        values = arrayOf(
                            "from" to "address",
                            "to" to "address",
                            "value" to "uint256",
                            "validAfter" to "uint256",
                            "validBefore" to "uint256",
                            "nonce" to "bytes32",
                        ),
                    ),
                )
            })
            put("primaryType", "TransferWithAuthorization")
            set<ObjectNode>("domain", objectMapper.createObjectNode().apply {
                put("name", required.tokenName)
                put("version", required.tokenVersion)
                put("chainId", CHAIN_ID)
                put("verifyingContract", required.selected.path("asset").textValue())
            })
            set<ObjectNode>("message", objectMapper.valueToTree(authorization))
        }
        val digest =
            StructuredDataEncoder(objectMapper.writeValueAsString(typedData)).hashStructuredData()
        val signed = Sign.signMessage(digest, credentials.ecKeyPair, false)
        return Numeric.toHexString(signed.r + signed.s + signed.v)
    }

    private fun fields(vararg values: Pair<String, String>): ArrayNode {
        return objectMapper.createArrayNode().apply {
            values.forEach { (name, type) ->
                add(objectMapper.createObjectNode().put("name", name).put("type", type))
            }
        }
    }

}
