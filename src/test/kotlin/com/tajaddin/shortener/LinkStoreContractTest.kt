package com.tajaddin.shortener

import com.tajaddin.shortener.store.DeleteResult
import com.tajaddin.shortener.store.ExposedLinkStore
import com.tajaddin.shortener.store.InMemoryLinkStore
import com.tajaddin.shortener.store.LinkStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    @Test
    fun `list pages newest first without gaps or repeats`() {
        val created = (1..5).map { create("https://example.com/$it").code }
        val paged = listOf(store.list(0, 2), store.list(2, 2), store.list(4, 2)).flatten().map { it.code }
        assertEquals(created.reversed(), paged)
    }

    @Test
    fun `deleted codes are never handed out again`() {
        val first = create("https://a.com")
        store.delete(first.code, owner)
        assertNotEquals(first.code, create("https://b.com").code)
    }

    @Test
    fun `generated codes skip codes already claimed as custom`() {
        // Claim a block of codes the generator will reach, so it has to step over them.
        val claimed = (1010L..1019L).map { ShortCode.encode(it) }
        claimed.forEach { create("https://claimed.com/$it", it) }

        val generated = create("https://new.com")
        assertFalse(generated.code in claimed)
        assertEquals("https://new.com", store.resolve(generated.code))
        claimed.forEach { assertEquals("https://claimed.com/$it", store.resolve(it)) }
    }

    @Test
    fun `concurrent creates all succeed with distinct codes`() {
        val codes = concurrently(200) { create("https://example.com/$it").code }
        assertEquals(200, codes.map { it.getOrThrow() }.toSet().size)
    }

    @Test
    fun `concurrent creates of one custom code have exactly one winner`() {
        val results = concurrently(16) { create("https://example.com/$it", "race") }
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertIs<CodeTakenException>(it.exceptionOrNull()) }
    }

    private fun <T> concurrently(n: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (1..n).map { i -> pool.submit<T> { start.await(); task(i) } }
            start.countDown()
            return futures.map { f -> runCatching { f.get() }.recoverCatching { throw (it as ExecutionException).cause!! } }
        } finally {
            pool.shutdown()
        }
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
