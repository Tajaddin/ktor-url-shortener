package com.tajaddin.shortener

import com.tajaddin.shortener.store.DeleteResult
import com.tajaddin.shortener.store.ExposedLinkStore
import com.tajaddin.shortener.store.InMemoryLinkStore
import com.tajaddin.shortener.store.LinkStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One contract, run against both store implementations, so the in-memory and
 * Exposed/H2 stores are guaranteed to behave identically.
 */
abstract class LinkStoreContractTest {

    abstract val store: LinkStore

    private val owner = OwnerToken.hash("owner-token")

    private fun create(target: String, code: String? = null) = store.create(target, code, owner)

    @Test
    fun `create generates a code and resolves to target`() {
        val link = create("https://example.com")
        assertTrue(link.code.isNotEmpty())
        assertEquals("https://example.com", store.resolve(link.code))
    }

    @Test
    fun `resolve increments hit count`() {
        val link = create("https://example.com/page")
        store.resolve(link.code)
        store.resolve(link.code)
        assertEquals(2, store.get(link.code)!!.hits)
    }

    @Test
    fun `get does not count a hit`() {
        val link = create("https://example.com")
        store.get(link.code)
        assertEquals(0, store.get(link.code)!!.hits)
    }

    @Test
    fun `custom code is honored`() {
        val link = create("https://example.com", "promo")
        assertEquals("promo", link.code)
        assertEquals("https://example.com", store.resolve("promo"))
    }

    @Test
    fun `duplicate custom code throws`() {
        create("https://a.com", "dup")
        assertFailsWith<CodeTakenException> { create("https://b.com", "dup") }
    }

    @Test
    fun `resolve of missing code returns null`() {
        assertNull(store.resolve("nope"))
    }

    @Test
    fun `delete removes the link`() {
        val link = create("https://example.com")
        assertEquals(DeleteResult.DELETED, store.delete(link.code, owner))
        assertEquals(DeleteResult.NOT_FOUND, store.delete(link.code, owner))
        assertNull(store.get(link.code))
    }

    @Test
    fun `delete with another owner's token keeps the link`() {
        val link = create("https://example.com")
        assertEquals(DeleteResult.NOT_OWNER, store.delete(link.code, OwnerToken.hash("someone-else")))
        assertNotNull(store.get(link.code))
    }

    @Test
    fun `list returns created links`() {
        create("https://a.com", "a")
        create("https://b.com", "b")
        val codes = store.list(0, 20).map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("a", "b")))
    }
}

class InMemoryLinkStoreTest : LinkStoreContractTest() {
    override val store: LinkStore = InMemoryLinkStore()
}

class ExposedLinkStoreTest : LinkStoreContractTest() {
    // Unique in-memory H2 db per test instance.
    private val h2 = ExposedLinkStore.fromJdbc("jdbc:h2:mem:test_${System.nanoTime()};DB_CLOSE_DELAY=-1")
    override val store: LinkStore = h2

    @AfterTest
    fun noop() {}
}
