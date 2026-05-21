package com.tajaddin.shortener

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortCodeTest {

    @Test
    fun `encode then decode round trips`() {
        for (id in listOf(0L, 1L, 61L, 62L, 1000L, 123456789L, Long.MAX_VALUE / 2)) {
            assertEquals(id, ShortCode.decode(ShortCode.encode(id)))
        }
    }

    @Test
    fun `encode is base62 and url-safe`() {
        val code = ShortCode.encode(1_000_000L)
        assertTrue(code.all { it.isLetterOrDigit() })
    }

    @Test
    fun `monotonic ids yield distinct codes`() {
        val codes = (1000L..2000L).map { ShortCode.encode(it) }.toSet()
        assertEquals(1001, codes.size)
    }

    @Test
    fun `decode rejects invalid characters`() {
        assertFailsWith<IllegalArgumentException> { ShortCode.decode("abc!def") }
    }

    @Test
    fun `custom code validation`() {
        assertTrue(ShortCode.isValidCustom("my-link_1"))
        assertTrue(ShortCode.isValidCustom("AbC123"))
        assertFalse(ShortCode.isValidCustom(""))
        assertFalse(ShortCode.isValidCustom("has space"))
        assertFalse(ShortCode.isValidCustom("toolong".repeat(10)))
    }
}
