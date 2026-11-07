package com.tajaddin.shortener

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * Per-link delete credential. The raw token is returned once, when the link is
 * created; stores keep only its SHA-256, so a leaked database grants no deletes.
 */
object OwnerToken {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
