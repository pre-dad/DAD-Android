package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReporterTest {
    @Test fun kindMapsAccountFailures() {
        assertEquals(ClientError.Kind.SIGNED_OUT, ClientError.Kind.of(AccountFailure.SignedOut))
        assertEquals(ClientError.Kind.UNAVAILABLE, ClientError.Kind.of(AccountFailure.Unavailable))
        assertEquals(ClientError.Kind.INVALID_RESPONSE, ClientError.Kind.of(AccountFailure.InvalidResponse))
        assertEquals(ClientError.Kind.UNKNOWN, ClientError.Kind.of(RuntimeException("boom")))
    }

    @Test fun dedupeKeyIsStablePerScreenOperationKind() {
        val a = ClientError("settings", "load", ClientError.Kind.SIGNED_OUT)
        val same = ClientError("settings", "load", ClientError.Kind.SIGNED_OUT)
        val otherOperation = ClientError("settings", "save", ClientError.Kind.SIGNED_OUT)
        val otherKind = ClientError("settings", "load", ClientError.Kind.UNAVAILABLE)
        assertEquals(a.dedupeKey, same.dedupeKey)
        assertTrue(a.dedupeKey != otherOperation.dedupeKey)
        assertTrue(a.dedupeKey != otherKind.dedupeKey)
    }

    private class RecordingReporter : ErrorReporter {
        val received = mutableListOf<ClientError>()
        override fun report(error: ClientError) { received.add(error) }
    }

    @Test fun dedupingReporterForwardsEachDistinctErrorOnce() {
        val sink = RecordingReporter()
        val reporter = DedupingErrorReporter(sink)
        val err = ClientError("events", "reserve", ClientError.Kind.UNAVAILABLE)
        reporter.report(err)
        reporter.report(err) // same key — dropped
        reporter.report(ClientError("events", "cancel", ClientError.Kind.UNAVAILABLE)) // different op — forwarded
        assertEquals(2, sink.received.size)
        assertEquals(2, reporter.reportedCount)
    }

    @Test fun failuresWithoutTheirOwnBucketAreUnknownAndNeverLeakTheirType() {
        listOf(AccountFailure.RateLimited, AccountFailure.Forbidden, AccountFailure.Conflict, java.io.IOException("socket"), IllegalStateException())
            .forEach { assertEquals(it.toString(), ClientError.Kind.UNKNOWN, ClientError.Kind.of(it)) }
    }

    @Test fun theSameOperationFailingDifferentlyReportsEachKindOnce() {
        val sink = RecordingReporter()
        val reporter = DedupingErrorReporter(sink)
        reporter.report(ClientError("home", "load", ClientError.Kind.UNAVAILABLE))
        reporter.report(ClientError("home", "load", ClientError.Kind.SIGNED_OUT))
        reporter.report(ClientError("home", "load", ClientError.Kind.UNAVAILABLE))
        assertEquals(listOf(ClientError.Kind.UNAVAILABLE, ClientError.Kind.SIGNED_OUT), sink.received.map { it.kind })
    }
}
