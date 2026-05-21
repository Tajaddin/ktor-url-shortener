package com.tajaddin.shortener.store

import com.tajaddin.shortener.CodeTakenException
import com.tajaddin.shortener.LinkResponse
import com.tajaddin.shortener.ShortCode
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * SQL-backed store using Exposed over a Hikari connection pool. Works on H2
 * (tests/demo) and PostgreSQL (production); pass the JDBC URL accordingly.
 */
class ExposedLinkStore private constructor(private val db: Database) : LinkStore {

    object Links : Table("links") {
        val code = varchar("code", 32)
        val target = varchar("target", 2048)
        val hits = long("hits").default(0)
        val createdAt = long("created_at")
        override val primaryKey = PrimaryKey(code)
    }

    init {
        transaction(db) { SchemaUtils.create(Links) }
    }

    override fun create(target: String, customCode: String?): LinkResponse = transaction(db) {
        val now = System.currentTimeMillis()
        if (customCode != null) {
            if (Links.selectAll().where { Links.code eq customCode }.empty().not()) {
                throw CodeTakenException(customCode)
            }
            Links.insert {
                it[code] = customCode
                it[Links.target] = target
                it[createdAt] = now
            }
            return@transaction LinkResponse(customCode, target, 0, now)
        }
        // Derive a code from the row count as a monotonic seed; retry on clash.
        var seed = 1000L + Links.selectAll().count()
        var result: LinkResponse? = null
        while (result == null) {
            val code = ShortCode.encode(seed)
            if (Links.selectAll().where { Links.code eq code }.empty()) {
                Links.insert {
                    it[Links.code] = code
                    it[Links.target] = target
                    it[createdAt] = now
                }
                result = LinkResponse(code, target, 0, now)
            } else {
                seed++
            }
        }
        result
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
            .orderBy(Links.createdAt, SortOrder.DESC)
            .map { it.toResponse() }
            .drop(offset)
            .take(limit)
    }

    override fun delete(code: String): Boolean = transaction(db) {
        Links.deleteWhere { Links.code eq code } > 0
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toResponse() = LinkResponse(
        code = this[Links.code],
        target = this[Links.target],
        hits = this[Links.hits],
        createdAtEpochMs = this[Links.createdAt],
    )

    companion object {
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
