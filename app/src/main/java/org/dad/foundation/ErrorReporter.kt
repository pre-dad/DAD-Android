package org.dad.foundation

/** One reported client error. [screen] and [operation] locate it; [kind] is a coarse bucket. */
data class ClientError(
    val screen: String,
    val operation: String,
    val kind: Kind,
) {
    enum class Kind { SIGNED_OUT, UNAVAILABLE, INVALID_RESPONSE, UNKNOWN;
        companion object {
            /** Map an AccountFailure (or anything) to a coarse, PII-free bucket. */
            fun of(error: Throwable): Kind = when (error) {
                is AccountFailure.SignedOut -> SIGNED_OUT
                is AccountFailure.Unavailable -> UNAVAILABLE
                is AccountFailure.InvalidResponse -> INVALID_RESPONSE
                else -> UNKNOWN
            }
        }
    }

    /** A stable de-dup key: the same error on the same screen/operation reports once per session. */
    val dedupeKey: String get() = "$screen|$operation|$kind"
}

/** The seam the screens report through. A conformer forwards to a crash/error backend. */
interface ErrorReporter {
    fun report(error: ClientError)
}

internal class ConsentErrorReporter(private val allowed: () -> Boolean, private val delegate: ErrorReporter) : ErrorReporter {
    override fun report(error: ClientError) { if (allowed()) delegate.report(error) }
}

/**
 * Wraps a reporter and drops repeats within a session (one failing load should not spam the
 * backend). Bounded and thread-safe because OkHttp reports from worker threads.
 */
class DedupingErrorReporter(private val delegate: ErrorReporter) : ErrorReporter {
    private val seen = mutableSetOf<String>()
    @Synchronized override fun report(error: ClientError) {
        if (seen.size < 256 && seen.add(error.dedupeKey)) {
            // Telemetry must never turn a recoverable API error into a failed user action.
            runCatching { delegate.report(error) }
        }
    }
    @Synchronized fun clear() { seen.clear() }
    /** Test/inspection aid: how many distinct errors have been forwarded this session. */
    val reportedCount: Int @Synchronized get() = seen.size
}
