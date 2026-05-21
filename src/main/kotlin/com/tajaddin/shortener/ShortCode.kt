package com.tajaddin.shortener

/**
 * Base62 codec for generating compact short codes from monotonic ids.
 * Alphabet is URL-safe (0-9, a-z, A-Z), so codes need no escaping.
 */
object ShortCode {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val BASE = 62

    /** Encode a non-negative id to a base62 string. */
    fun encode(id: Long): String {
        require(id >= 0) { "id must be non-negative" }
        if (id == 0L) return "0"
        val sb = StringBuilder()
        var n = id
        while (n > 0) {
            sb.append(ALPHABET[(n % BASE).toInt()])
            n /= BASE
        }
        return sb.reverse().toString()
    }

    /** Decode a base62 string back to its id. Throws on invalid characters. */
    fun decode(code: String): Long {
        var n = 0L
        for (c in code) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base62 character: $c" }
            n = n * BASE + v
        }
        return n
    }

    private val CUSTOM_CODE = Regex("^[0-9a-zA-Z_-]{1,32}$")

    /** Validate a user-supplied custom code. */
    fun isValidCustom(code: String): Boolean = CUSTOM_CODE.matches(code)
}
