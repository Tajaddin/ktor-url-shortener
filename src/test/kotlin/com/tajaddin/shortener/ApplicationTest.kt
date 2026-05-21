package com.tajaddin.shortener

import com.tajaddin.shortener.store.InMemoryLinkStore
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
    fun `delete then 404`() = appTest { client ->
        client.post("/api/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com","code":"gone"}""")
        }
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/links/gone").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/links/gone").status)
    }
}
