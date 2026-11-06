package com.tajaddin.shortener

import com.tajaddin.shortener.store.DeleteResult
import com.tajaddin.shortener.store.LinkStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest

private val ALLOWED_SCHEMES = setOf("http", "https")

/**
 * Creating and resolving links is public. Deleting a link needs the owner
 * token returned when it was created; listing every link needs [adminKey].
 * Both are sent as `Authorization: Bearer <token>`.
 */
fun Application.configureRouting(store: LinkStore, adminKey: String?) {
    routing {
        get("/healthz") { call.respond(mapOf("status" to "ok")) }

        post("/api/links") {
            val req = call.receive<CreateLinkRequest>()
            validateUrl(req.url)
            req.code?.let {
                if (!ShortCode.isValidCustom(it)) {
                    throw InvalidUrlException("custom code must match [0-9a-zA-Z_-]{1,32}")
                }
            }
            val ownerToken = OwnerToken.generate()
            val link = store.create(req.url, req.code, OwnerToken.hash(ownerToken))
            call.respond(
                HttpStatusCode.Created,
                CreatedLinkResponse(link.code, link.target, link.hits, link.createdAtEpochMs, ownerToken),
            )
        }

        get("/api/links") {
            if (!call.isAdmin(adminKey)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("admin key required"))
            }
            val offset = call.queryInt("offset", 0).coerceAtLeast(0)
            val limit = call.queryInt("limit", 20).coerceIn(1, 100)
            call.respond(PagedLinks(store.list(offset, limit), offset, limit))
        }

        get("/api/links/{code}") {
            val code = call.parameters["code"]!!
            val link = store.get(code)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no link for code $code"))
            call.respond(link)
        }

        delete("/api/links/{code}") {
            val code = call.parameters["code"]!!
            val token = call.bearerToken()
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("owner token required"))
            when (store.delete(code, OwnerToken.hash(token))) {
                DeleteResult.DELETED -> call.respond(HttpStatusCode.NoContent)
                DeleteResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no link for code $code"))
                DeleteResult.NOT_OWNER -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("not the owner of $code"))
            }
        }

        // Redirect hot path. Kept last so it does not shadow /api or /healthz.
        get("/{code}") {
            val code = call.parameters["code"]!!
            val target = store.resolve(code)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no link for code $code"))
            call.respondRedirect(target, permanent = false)
        }
    }
}

private fun validateUrl(url: String) {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme !in ALLOWED_SCHEMES) {
        throw InvalidUrlException("url must start with http:// or https://")
    }
    if (url.length > 2048) {
        throw InvalidUrlException("url too long (max 2048)")
    }
}

private fun ApplicationCall.queryInt(name: String, default: Int): Int =
    request.queryParameters[name]?.toIntOrNull() ?: default

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring("Bearer ".length).trim().ifEmpty { null }
}

/** Constant-time comparison; with no key configured, nobody is admin. */
private fun ApplicationCall.isAdmin(adminKey: String?): Boolean {
    val presented = bearerToken() ?: return false
    return adminKey != null && MessageDigest.isEqual(presented.toByteArray(), adminKey.toByteArray())
}
