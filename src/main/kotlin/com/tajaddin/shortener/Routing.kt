package com.tajaddin.shortener

import com.tajaddin.shortener.store.LinkStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

private val ALLOWED_SCHEMES = setOf("http", "https")

fun Application.configureRouting(store: LinkStore) {
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
            val link = store.create(req.url, req.code)
            call.respond(HttpStatusCode.Created, link)
        }

        get("/api/links") {
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
            if (store.delete(code)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no link for code $code"))
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

private fun io.ktor.server.application.ApplicationCall.queryInt(name: String, default: Int): Int =
    request.queryParameters[name]?.toIntOrNull() ?: default
