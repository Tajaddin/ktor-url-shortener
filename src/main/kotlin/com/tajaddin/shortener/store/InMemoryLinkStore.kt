package com.tajaddin.shortener.store

import com.tajaddin.shortener.CodeTakenException
import com.tajaddin.shortener.LinkResponse
import com.tajaddin.shortener.ShortCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory store. The hit counter is an AtomicLong so the
 * redirect hot path takes no lock: a ConcurrentHashMap get plus an atomic
 * increment.
 */
class InMemoryLinkStore : LinkStore {

    private class Record(
        val code: String,
        val target: String,
        val createdAtEpochMs: Long,
        val hits: AtomicLong = AtomicLong(0),
    )

    private val byCode = ConcurrentHashMap<String, Record>()
    private val idSeq = AtomicLong(1000)

    override fun create(target: String, customCode: String?): LinkResponse {
        val now = System.currentTimeMillis()
        if (customCode != null) {
            val rec = Record(customCode, target, now)
            if (byCode.putIfAbsent(customCode, rec) != null) {
                throw CodeTakenException(customCode)
            }
            return rec.toResponse()
        }
        // Generate from a monotonic id; retry on the rare collision with a
        // previously-claimed custom code.
        while (true) {
            val code = ShortCode.encode(idSeq.getAndIncrement())
            val rec = Record(code, target, now)
            if (byCode.putIfAbsent(code, rec) == null) {
                return rec.toResponse()
            }
        }
    }

    override fun resolve(code: String): String? {
        val rec = byCode[code] ?: return null
        rec.hits.incrementAndGet()
        return rec.target
    }

    override fun get(code: String): LinkResponse? = byCode[code]?.toResponse()

    override fun list(offset: Int, limit: Int): List<LinkResponse> =
        byCode.values
            .sortedByDescending { it.createdAtEpochMs }
            .drop(offset)
            .take(limit)
            .map { it.toResponse() }

    override fun delete(code: String): Boolean = byCode.remove(code) != null

    private fun Record.toResponse() =
        LinkResponse(code = code, target = target, hits = hits.get(), createdAtEpochMs = createdAtEpochMs)
}
