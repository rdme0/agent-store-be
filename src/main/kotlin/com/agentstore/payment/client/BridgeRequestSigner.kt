package com.agentstore.payment.client

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BridgeRequestSigner(private val secret: String) {
    fun bodyHash(body: ByteArray): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
    }

    fun signature(method: String, path: String, timestamp: String, nonce: String, hash: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return HexFormat.of()
            .formatHex(mac.doFinal("$method\n$path\n$timestamp\n$nonce\n$hash".toByteArray(StandardCharsets.UTF_8)))
    }
}
