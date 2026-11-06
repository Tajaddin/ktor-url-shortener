package com.tajaddin.shortener.store

import com.tajaddin.shortener.CodeTakenException
import com.tajaddin.shortener.LinkResponse
import com.tajaddin.shortener.ShortCode
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import javax.sql.DataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Sequence
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.nextLongVal
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * SQL-backed store using Exposed over a Hikari connection pool. Works on H2
 * (tests/demo) and PostgreSQL (production); pass the JDBC URL accordingly.
 */
class ExposedLinkStore private constructor(private val db: Database) : LinkStore {

    object Links : Table("links") {
        val id = long("id").uniqueIndex()
        val code = varchar("code", 32)
        val target = varchar("target", 2048)
        val hits = long("hits").default(0)
        val createdAt = long("created_at")
        val ownerTokenHash = varchar("owner_token_hash", 64)
        override val primaryKey = PrimaryKey(code)
    }

    /** Ids are never reused, so a deleted link's generated code is never handed out again. */
    private val idSequence = Sequence("links_id_seq", startWith = 1000)
    private val nextId = idSequence.nextLongVal()

    init {
        transaction(db) {
            SchemaUtils.createSequence(idSequence)
            SchemaUtils.create(Links)
        }
    }

    override fun create(target: String, customCode: String?, ownerTokenHash: String): LinkResponse {
        // Fast path for the common case; the unique constraint still settles races.
        if (customCode != null && get(customCode) != null) throw CodeTakenException(customCode)
        while (true) {
            tryInsert(target, customCode, ownerTokenHash)?.let { return it }
            if (customCode != null) throw CodeTakenException(customCode)
            // A generated code can only clash with an earlier custom code; the next id gives a new one.
        }
    }

    /**
     * One insert per transaction, with the unique constraint as the only
     * arbiter between concurrent creates. Returns null if the code is taken.
     */
    private fun tryInsert(target: String, customCode: String?, ownerTokenHash: String): LinkResponse? = try {
        transaction(db) {
            maxAttempts = 1
            val id = Table.Dual.select(nextId).single()[nextId]
            val code = customCode ?: ShortCode.encode(id)
            val now = System.currentTimeMillis()
            Links.insert {
                it[Links.id] = id
                it[Links.code] = code
                it[Links.target] = target
                it[createdAt] = now
                it[Links.ownerTokenHash] = ownerTokenHash
            }
            LinkResponse(code, target, 0, now)
        }
    } catch (e: SQLException) {
        if (e.sqlState == UNIQUE_VIOLATION) null else throw e
    }

    override fun resolve(code: String): String? = transaction(db) {
        val row = Links.selectAll().where { Links.code eq code }.singleOrNull() ?: return@transaction null
        Links.update({ Links.code eq code }) {
            with(SqlExpressionBuilder) { it[hits] = hits + 1 }
        }
        row[Links.target]
    }

    override fun get(code: String): LinkResponse? = transaction(db) {
        Links.selectAll().where { Links.code eq code }.singleOrNull()?.toResponse()
    }

    override fun list(offset: Int, limit: Int): List<LinkResponse> = transaction(db) {
        Links.selectAll()
            .orderBy(Links.id, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toResponse() }
    }

    override fun delete(code: String, ownerTokenHash: String): DeleteResult = transaction(db) {
        val deleted = Links.deleteWhere { (Links.code eq code) and (Links.ownerTokenHash eq ownerTokenHash) }
        when {
            deleted > 0 -> DeleteResult.DELETED
            Links.selectAll().where { Links.code eq code }.empty() -> DeleteResult.NOT_FOUND
            else -> DeleteResult.NOT_OWNER
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toResponse() = LinkResponse(
        code = this[Links.code],
        target = this[Links.target],
        hits = this[Links.hits],
        createdAtEpochMs = this[Links.createdAt],
    )

    companion object {
        /** SQLSTATE for a unique-constraint violation on both H2 and PostgreSQL. */
        private const val UNIQUE_VIOLATION = "23505"

        fun fromJdbc(url: String, user: String = "", password: String = ""): ExposedLinkStore {
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 10
            }
            return fromDataSource(HikariDataSource(cfg))
        }

        fun fromDataSource(ds: DataSource): ExposedLinkStore =
            ExposedLinkStore(Database.connect(ds))
    }
}
