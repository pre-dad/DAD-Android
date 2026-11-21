package org.dad.foundation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticsTest {
    @Test fun reportsRequireCurrentConsentAndAreNotQueuedWhileDeclined() {
        var allowed = false
        val received = mutableListOf<ClientError>()
        val sink = object : ErrorReporter { override fun report(error: ClientError) { received += error } }
        val reporter = ConsentErrorReporter({ allowed }, DedupingErrorReporter(sink))
        val error = ClientError("api", "profile", ClientError.Kind.UNAVAILABLE)
        reporter.report(error)
        assertTrue(received.isEmpty())
        allowed = true
        reporter.report(error)
        assertEquals(listOf(error), received)
        allowed = false
        reporter.report(error.copy(operation = "settings"))
        assertEquals(1, received.size)
    }

    @Test fun concurrentFailuresAreDeduplicatedAndReportingIsBounded() {
        val received = java.util.concurrent.CopyOnWriteArrayList<ClientError>()
        val reporter = DedupingErrorReporter(object : ErrorReporter { override fun report(error: ClientError) { received += error } })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(1000) { pool.submit { reporter.report(ClientError("api", "profile", ClientError.Kind.UNAVAILABLE)) } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(1, received.size)
            repeat(1000) { reporter.report(ClientError("api", "operation_$it", ClientError.Kind.UNAVAILABLE)) }
            assertEquals(256, reporter.reportedCount)
            reporter.clear()
            reporter.report(ClientError("api", "profile", ClientError.Kind.UNAVAILABLE))
            assertEquals(1, reporter.reportedCount)
        } finally { pool.shutdownNow() }
    }

    @Test fun apiErrorsAreReportedWithoutBodiesPathsOrCredentials() = runTest {
        val received = mutableListOf<ClientError>()
        val sink = object : ErrorReporter { override fun report(error: ClientError) { received += error } }
        val http = ScriptedHttp { 503 to """{"detail":"private email alex@example.org"}""" }
        val client = http.client().newBuilder().apply { interceptors().add(0, ErrorObserver(sink)) }.build()
        assertEquals(AccountFailure.Unavailable, runCatching { WireAPI(TEST_ORIGIN, client).profile("secret-token") }.exceptionOrNull())
        val error = received.single()
        assertEquals("profile", error.operation)
        assertEquals("api", error.screen)
        assertFalse(error.toString().contains("alex@example.org"))
        assertFalse(error.toString().contains("secret-token"))
    }

    @Test fun aCancelledCallIsNotReportedAsAnOutageButARealFailureIs() {
        val received = mutableListOf<ClientError>()
        val sink = object : ErrorReporter { override fun report(error: ClientError) { received += error } }
        fun client(cancel: Boolean) = okhttp3.OkHttpClient.Builder()
            .addInterceptor(ErrorObserver(sink))
            .addInterceptor { chain ->
                if (cancel) chain.call().cancel()
                throw java.io.IOException(if (cancel) "Canceled" else "unreachable")
            }
            .build()
        val request = okhttp3.Request.Builder().url("http://localhost/").build()
        assertTrue(runCatching { client(cancel = true).newCall(request).execute() }.exceptionOrNull() is java.io.IOException)
        assertTrue(received.isEmpty())
        assertTrue(runCatching { client(cancel = false).newCall(request).execute() }.exceptionOrNull() is java.io.IOException)
        assertEquals(listOf(ClientError.Kind.UNAVAILABLE), received.map { it.kind })
    }
}
