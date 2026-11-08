package org.dad.foundation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Remembers that the Play install referrer was read, so a later launch never captures it again. */
interface InstallReferrerLedger {
    fun wasRead(): Boolean
    fun markRead()
}

/** SharedPreferences, beside the other attribution state; not a credential. Survives an account wipe on purpose. */
class PrefsInstallReferrerLedger(context: Context) : InstallReferrerLedger {
    private val prefs = context.getSharedPreferences("org.dad.attribution", Context.MODE_PRIVATE)
    override fun wasRead() = prefs.getBoolean(KEY, false)
    override fun markRead() = prefs.edit { putBoolean(KEY, true) }

    private companion object { const val KEY = "install-referrer-read" }
}

class MemoryInstallReferrerLedger(private var read: Boolean = false) : InstallReferrerLedger {
    override fun wasRead() = read
    override fun markRead() { read = true }
}

/**
 * First-launch deferred attribution (D-3): reads the Play install referrer once, parses it with
 * [InstallReferrerParser] and hands the codes to [LinkAttribution], the same path a tapped invite link takes
 * (`POST /donor/referrals/{code}`, `POST /donor/company/{code}`), which also emits the `attribution_captured` event.
 *
 * The referrer is marked read as soon as it has been asked for, whatever it said, so a launch never captures twice.
 * That makes the capture itself best effort: a code whose network call fails is not retried. A launch that is
 * cancelled before the referrer answers (the app was closed) leaves it unread for the next one.
 */
class InstallReferrerIntake(
    private val referrer: InstallReferrer,
    private val ledger: InstallReferrerLedger,
    private val links: LinkAttribution,
    private val timeoutMs: Long = READ_TIMEOUT_MS,
) {
    /** Returns the referrer's public name when the server named one, else null. Never throws except to cancel. */
    suspend fun run(): String? {
        if (ledger.wasRead()) return null
        val raw = try {
            withTimeoutOrNull(timeoutMs) { referrer.fetch() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        ledger.markRead()
        if (raw == null || raw.length > MAX_REFERRER_LENGTH) return null
        val attribution = InstallReferrerParser.parse(raw)
        var name: String? = null
        attribution.inviteCode?.let { name = links.captureReferral(it, inPerson = false) }
        attribution.irlCode?.let { code -> links.captureReferral(code, inPerson = true)?.let { name = name ?: it } }
        attribution.companyCode?.let { links.captureCompany(it) }
        return name
    }

    companion object {
        const val READ_TIMEOUT_MS = 5_000L
        /** Far longer than any real referrer (a few short parameters); anything bigger is not read. */
        const val MAX_REFERRER_LENGTH = 1024
    }
}
