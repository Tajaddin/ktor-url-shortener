# ktor-url-shortener

> Coroutine-native URL shortener on Ktor 3 + Netty. **~1,500 redirects/sec at p99 ~61 ms** on the 302 hot path (in-memory store, lock-free atomic hit counters, 50 concurrent clients, 8000/8000 OK). Pluggable storage (in-memory or Exposed/Postgres) behind one interface, 44 tests including a store contract run against both backends.

[![ci](https://github.com/Tajaddin/ktor-url-shortener/actions/workflows/ci.yml/badge.svg)](https://github.com/Tajaddin/ktor-url-shortener/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1-7F52FF)](build.gradle.kts)
[![Ktor](https://img.shields.io/badge/ktor-3.0-087CFA)](build.gradle.kts)

## Hero metrics

Reproducible (in-memory store, no DB):

```bash
./gradlew run            # server on :8080
python load/load_probe.py --base http://localhost:8080 --requests 8000 --concurrency 50
```

Last measured 3-run baseline (full output + hardware in [`bench/results.txt`](bench/results.txt)):

| Metric | Value |
|---|---:|
| **Redirect throughput** | **~1,500 req/s** (3-run median 1,500; max 1,536) |
| Latency p99 | **~61 ms** |
| Success rate | 8000 / 8000 (100%) per run |

Measured on `GET /{code}` returning a 302 (redirects not followed by the probe, so no external network). The redirect path takes no lock: a `ConcurrentHashMap` get plus an `AtomicLong` increment. The lock-free read path and Netty's non-blocking model keep p99 comfortably under 80 ms after JIT warmup.

## What it is

A URL shortener that demonstrates idiomatic Kotlin + Ktor:

| Concern | Implementation |
|---|---|
| HTTP | Ktor 3 on Netty, coroutine-native handlers, `MapGroup`-style routing |
| Serialization | kotlinx.serialization JSON via Ktor ContentNegotiation |
| Codec | Base62 short-code encoder/decoder (URL-safe, round-trip tested) |
| Storage | `LinkStore` interface with two impls: `InMemoryLinkStore` (lock-free hot path) and `ExposedLinkStore` (Exposed + HikariCP over H2 / Postgres; codes from a database sequence, paging done in SQL) |
| Hot path | Redirect = map get + atomic hit increment, no transaction, no lock |
| Errors | `StatusPages` maps domain exceptions to 400 / 409 JSON; anything else is logged and returns a generic 500 |
| Validation | Scheme allow-list (http/https), length cap, custom-code regex |

## Why this matters for hiring

Role categories unlocked: **Backend-Kotlin**, JVM backend, microservices.

Kotlin + Ktor + coroutines is the modern JVM alternative to Spring. This repo backs the "Kotlin / Ktor" resume line with a real service, a pluggable persistence layer, and a load-measured redirect path. (It also substitutes for an iOS sample, which cannot be built on a non-macOS host.)

## How to run

Prerequisites: JDK 21+ (the included Gradle wrapper handles the rest); Docker optional for the Postgres compose stack.

```bash
./gradlew test                      # 44 unit + integration tests (in-memory + H2)
./gradlew run                       # ws://localhost:8080, in-memory store
python load/load_probe.py --base http://localhost:8080 \
       --requests 8000 --concurrency 50   # reproduces the redirect hero
```

### In-memory (zero setup)

```bash
./gradlew run
```

```bash
# create
curl -s -XPOST localhost:8080/api/links -H 'content-type: application/json' \
  -d '{"url":"https://example.com/landing"}'
# -> {"code":"g8","target":"https://example.com/landing","hits":0,"createdAtEpochMs":...,"ownerToken":"..."}

# custom code
curl -s -XPOST localhost:8080/api/links -H 'content-type: application/json' \
  -d '{"url":"https://kotlinlang.org","code":"kt"}'

# redirect (302 -> target), increments hits
curl -si localhost:8080/kt | head -1

# stats
curl -s localhost:8080/api/links/kt

# delete: only with the ownerToken returned when the link was created
curl -si -XDELETE localhost:8080/api/links/kt -H "authorization: Bearer $OWNER_TOKEN"

# list every link: start the server with ADMIN_KEY set
curl -s localhost:8080/api/links -H "authorization: Bearer $ADMIN_KEY"
```

The owner token is shown once; the store keeps only its SHA-256. Without `ADMIN_KEY` the list endpoint refuses every request.

### Postgres (Exposed)

```bash
docker compose up --build   # app on :8080, Postgres on :5432
```

Switch stores at runtime with `STORE=postgres|h2|memory` (default memory).

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/links` | Create (optional custom `code`; 409 on duplicate, 400 on bad url); returns a one-time `ownerToken` |
| GET | `/api/links` | List newest-first (`offset`, `limit`); needs `Bearer $ADMIN_KEY`, else 401 |
| GET | `/api/links/{code}` | Link stats (does not count a hit) |
| DELETE | `/api/links/{code}` | Delete; needs `Bearer <ownerToken>` (401 without one, 403 for another link's token) |
| GET | `/{code}` | 302 redirect to target, increments hit counter |
| GET | `/healthz` | Health check |

## Testing

```bash
./gradlew test     # 44 tests on JDK 21
```

- **ShortCodeTest** (5): base62 encode/decode round trip, url-safety, distinctness, invalid-char rejection, custom-code validation.
- **LinkStoreContractTest** (14, run against BOTH `InMemoryLinkStore` and `ExposedLinkStore`/H2 = 28 executions): create/resolve, hit counting, get-does-not-count, custom code, duplicate-code conflict, missing resolve, delete, delete with another owner's token, list, paging without gaps or repeats, deleted codes never reused, generated codes stepping over claimed custom codes, 200 concurrent creates with distinct codes, one winner when 16 threads race for the same custom code.
- **ApplicationTest** (11): full Ktor `testApplication` HTTP flow — health, create+302 redirect with `Location`, custom-code + hit reporting, duplicate 409, invalid-url 400, unknown-code 404, delete needing the owner token (401 / 403 / 204), listing needing the admin key, malformed-body 400, 500s that do not echo internal messages.

One contract run against both stores guarantees the in-memory and SQL backends behave identically.

## Project layout

```
src/main/kotlin/com/tajaddin/shortener/
  Application.kt        # embeddedServer(Netty) + module wiring + store-from-env
  Routing.kt           # routes incl. the redirect hot path
  Plugins.kt           # StatusPages exception mapping
  Models.kt            # serializable DTOs + domain exceptions
  ShortCode.kt         # base62 codec
  store/
    LinkStore.kt        # interface
    InMemoryLinkStore.kt   # ConcurrentHashMap + AtomicLong hits
    ExposedLinkStore.kt    # Exposed + HikariCP (H2 / Postgres)
load/load_probe.py     # redirect-path load probe (no-follow)
```

## Stack

Kotlin 2.1, Ktor 3.0 (Netty), kotlinx.serialization, Exposed 0.57 + HikariCP, H2 / PostgreSQL, JUnit5 via kotlin-test, Gradle (wrapper), Docker (alpine multi-stage), GitHub Actions. Targets JVM 21.

## License

MIT
