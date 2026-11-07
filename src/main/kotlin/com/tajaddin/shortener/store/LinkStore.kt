package com.tajaddin.shortener.store

import com.tajaddin.shortener.LinkResponse

/**
 * Storage abstraction for short links. Two implementations:
 * - InMemoryLinkStore: ConcurrentHashMap, used by tests, the demo, and the
 *   throughput benchmark (the redirect hot path).
 * - ExposedLinkStore: SQL-backed (H2 / Postgres) for durable deployments.
 */
interface LinkStore {
    /**
     * Create a link for [target], owned by whoever holds the token hashing to
     * [ownerTokenHash]. If [customCode] is given it must be unique; otherwise a
     * base62 code is generated. Returns the created link.
     */
    fun create(target: String, customCode: String?, ownerTokenHash: String): LinkResponse

    /** Resolve [code] to its target URL and increment the hit counter. */
    fun resolve(code: String): String?

    /** Fetch a link's metadata without counting a hit. */
    fun get(code: String): LinkResponse?

    /** List links, newest first. */
    fun list(offset: Int, limit: Int): List<LinkResponse>

    /** Delete a link if [ownerTokenHash] matches the one it was created with. */
    fun delete(code: String, ownerTokenHash: String): DeleteResult
}

enum class DeleteResult { DELETED, NOT_FOUND, NOT_OWNER }
