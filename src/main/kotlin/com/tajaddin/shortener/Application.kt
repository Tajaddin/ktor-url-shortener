package com.tajaddin.shortener

import com.tajaddin.shortener.store.ExposedLinkStore
import com.tajaddin.shortener.store.InMemoryLinkStore
import com.tajaddin.shortener.store.LinkStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import org.slf4j.event.Level

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

/**
 * Wire the store from env: STORE=postgres uses Exposed over DATABASE_URL,
 * anything else (default) uses the in-memory store. The store is injectable
 * so tests pass their own. ADMIN_KEY unlocks GET /api/links; there is no
 * default, so without it listing is refused.
 */
fun Application.module(
    store: LinkStore = storeFromEnv(),
    adminKey: String? = System.getenv("ADMIN_KEY")?.takeIf { it.isNotBlank() },
) {
    if (adminKey == null) log.warn("ADMIN_KEY is not set; GET /api/links will refuse every request")
    install(ContentNegotiation) { json() }
    install(CallLogging) { level = Level.INFO }
    configureStatusPages()
    configureRouting(store, adminKey)
}

private fun storeFromEnv(): LinkStore {
    return when (System.getenv("STORE")?.lowercase()) {
        "postgres" -> {
            val url = System.getenv("DATABASE_URL")
                ?: "jdbc:postgresql://localhost:5432/shortener"
            ExposedLinkStore.fromJdbc(
                url,
                System.getenv("DATABASE_USER") ?: "shortener",
                System.getenv("DATABASE_PASSWORD") ?: "shortener",
            )
        }
        "h2" -> ExposedLinkStore.fromJdbc("jdbc:h2:mem:shortener;DB_CLOSE_DELAY=-1")
        else -> InMemoryLinkStore()
    }
}
