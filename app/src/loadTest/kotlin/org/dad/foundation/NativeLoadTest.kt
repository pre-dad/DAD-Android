package org.dad.foundation

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real session/Retrofit/OkHttp code over a synthetic transport; never a live provider load test. */
class NativeLoadTest {
    private class Harness : AutoCloseable {
        val refreshes = AtomicInteger()
        val http = ScriptedHttp { request ->
            when (request.path) {
                "/donor/refresh" -> {
                    refreshes.incrementAndGet()
                    200 to nativeSessionJson("fresh-access", "fresh-refresh")
                }
                "/donor/me/profile" -> if (request.headers["authorization"] == "Bearer fresh-access") 200 to profileJson() else 401 to "{}"
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val client = http.client()
        val api = WireAPI(TEST_ORIGIN, client)
        override fun close() {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun burst(size: Int, harness: Harness) = coroutineScope {
        val before = harness.refreshes.get()
        val store = RecordingStore(Credentials("expired", "refresh", 0))
        val lifetime = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            val session = AccountSession(harness.api, store, lifetime)
            val profiles = List(size) { async { session.profile() } }.awaitAll()
            assertEquals(size, profiles.size)
            assertTrue(profiles.all { it.email == "alex@example.org" })
            assertEquals("one refresh per expired session under contention", before + 1, harness.refreshes.get())
            assertEquals(1, store.saves)
            assertEquals("fresh-refresh", store.value?.refresh)
            session.discardLocal()
            assertNull(store.value)
            assertEquals(AccountFailure.SignedOut, runCatching { session.profile() }.exceptionOrNull())
        } finally {
            lifetime.cancel()
            harness.http.requests.clear()
        }
    }

    @Test fun burstOf128ReadsSharesOneRefresh() = runBlocking {
        Harness().use { harness -> withTimeout(30_000) { burst(128, harness) } }
    }

    @Test fun soakRepeatedlyRefreshesAndRevokesIndependentSessions() = runBlocking {
        val seconds = (System.getProperty("dad.load.soakSeconds") ?: "60").toLong()
        require(seconds in 60..3600) { "dadSoakSeconds must be between 60 and 3600" }
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        var completed = 0
        Harness().use { harness ->
            withTimeout((seconds + 30) * 1000) {
                while (System.nanoTime() < deadline) {
                    burst(16, harness)
                    completed++
                    delay(100)
                }
            }
        }
        assertTrue("the soak must exercise repeated session lifecycles", completed >= 20)
        println("Native ${seconds}s soak completed $completed sessions and ${completed * 16} authenticated profile reads.")
    }
}
