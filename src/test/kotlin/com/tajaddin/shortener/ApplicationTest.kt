package com.tajaddin.shortener

import com.tajaddin.shortener.store.InMemoryLinkStore
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationTest {

    private fun appTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { module(InMemoryLinkStore()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client)
    }

    @Test
    fun `health endpoint`() = appTest { client ->
        val resp = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `create then redirect`() = appTest { client ->
        val created = client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/landing"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val code = Regex("\"code\":\"([^\"]+)\"").find(created.bodyAsText())!!.groupValues[1]

        // Redirect must be 302 with the Location header; do not auto-follow.
        val noFollow = createClient { followRedirects = false }
        val redirect = noFollow.get("/$code")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("https://example.com/landing", redirect.headers["Location"])
    }

    @Test
    fun `custom code roundtrip and get reports hits`() = appTest { client ->
        client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com","code":"promo"}""")
        }
        val noFollow = createClient { followRedirects = false }
        noFollow.get("/promo")
        noFollow.get("/promo")

        val detail = client.get("/api/links/promo")
        assertEquals(HttpStatusCode.OK, detail.status)
        assertTrue(detail.bodyAsText().contains("\"hits\":2"), detail.bodyAsText())
    }

    @Test
    fun `duplicate custom code returns 409`() = appTest { client ->
        repeat(1) {
            client.post("/api/links") {
                contentType(ContentType.Application.Json)
                setBody("""{"url":"https://a.com","code":"dup"}""")
            }
        }
        val second = client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://b.com","code":"dup"}""")
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    @Test
    fun `invalid url returns 400`() = appTest { client ->
        val resp = client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"ftp://nope.com"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `unknown code redirect returns 404`() = appTest { client ->
        val noFollow = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.NotFound, noFollow.get("/missing").status)
    }

    @Test
    fun `delete needs the owner token returned on create`() = appTest { client ->
        val created = client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com","code":"gone"}""")
        }.body<CreatedLinkResponse>()
        assertTrue(created.ownerToken.length >= 32)

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/links/gone").status)
        val stranger = client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/other"}""")
        }.body<CreatedLinkResponse>()
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/links/gone") { bearerAuth(stranger.ownerToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/links/gone").status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/links/gone") { bearerAuth(created.ownerToken) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/links/gone").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/links/gone") { bearerAuth(created.ownerToken) }.status)
    }

    @Test
    fun `listing needs the admin key`() = testApplication {
        application { module(InMemoryLinkStore(), adminKey = "test-admin-key") }
        client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com","code":"listed"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/links").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/links") { bearerAuth("wrong-key") }.status)

        val listed = client.get("/api/links") { bearerAuth("test-admin-key") }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue(listed.bodyAsText().contains("\"listed\""), listed.bodyAsText())
        assertFalse(listed.bodyAsText().contains("ownerToken"), listed.bodyAsText())
    }

    @Test
    fun `listing is refused when no admin key is configured`() = testApplication {
        application { module(InMemoryLinkStore(), adminKey = null) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/links") { bearerAuth("anything") }.status)
    }
}
