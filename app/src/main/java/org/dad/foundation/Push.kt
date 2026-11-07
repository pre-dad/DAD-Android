package org.dad.foundation

import androidx.core.content.edit
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.model.RegisterPushDeviceRequest

/** This installation's push token (an FCM registration token), or null when push is not available on this build. */
fun interface PushTokenProvider {
    suspend fun token(): String?
}

/**
 * A provider that never throws: it answers null while [available] is false (Firebase is not configured, so no
 * `google-services.json` was built in) and when [fetch] fails, and rethrows only cancellation.
 */
class GuardedPushTokenProvider(
    private val available: () -> Boolean,
    private val fetch: suspend () -> String?,
) : PushTokenProvider {
    override suspend fun token(): String? {
        if (!available()) return null
        return try { fetch()?.takeIf { it.isNotBlank() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

/** Token rotations reported by the platform while the app process is alive; the model re-registers on each. */
object PushTokenRelay {
    private val rotations = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val refreshed: kotlinx.coroutines.flow.SharedFlow<String> get() = rotations
    fun rotated(token: String) { rotations.tryEmit(token) }
}

/** The push categories a donor can opt out of in Settings, keyed by the server `category` and the Settings toggle. */
enum class PushCategory(val wire: String, val settingsKey: String, val label: String) {
    REFERRAL_CONVERTED("REFERRAL_CONVERTED", "push_referral_converted", "Someone joined through you"),
    STREAK_AT_RISK("STREAK_AT_RISK", "push_streak_at_risk", "Streak at risk"),
    STREAK_MILESTONE("STREAK_MILESTONE", "push_streak_milestone", "Streak milestone"),
    STREAK_LAPSED("STREAK_LAPSED", "push_streak_lapsed", "Streak lapsed"),
    BADGE_EARNED("BADGE_EARNED", "push_badge_earned", "Badge earned"),
    EVENT_NEARBY("EVENT_NEARBY", "push_event_nearby", "Events nearby"),
    EVENT_REMINDER("EVENT_REMINDER", "push_event_reminder", "Event reminders"),
    EVENT_PROMOTED("EVENT_PROMOTED", "push_event_promoted", "Event promoted"),
    GIFT_PROCESSED("GIFT_PROCESSED", "push_gift_processed", "Gift processed");

    val channelId: String get() = "push_" + wire.lowercase()

    companion object {
        fun of(wire: String?): PushCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** The donor's per-category choices as last seen in Settings; a category never opted out of is allowed. */
interface PushOptOuts {
    fun allows(category: PushCategory): Boolean
}

/** Keeps the opt-outs on the device so a notification can be dropped without the network or a session. */
class PrefsPushOptOuts(context: android.content.Context) : PushOptOuts {
    private val prefs = context.applicationContext.getSharedPreferences("org.dad.push", android.content.Context.MODE_PRIVATE)
    override fun allows(category: PushCategory) = category.settingsKey !in prefs.getStringSet(KEY, emptySet()).orEmpty()
    fun record(preferences: Preferences) {
        prefs.edit { putStringSet(KEY, preferences.pushToggles.filterNot { it.value }.map { it.key }.toSet()) }
    }
    fun clear() { prefs.edit { remove(KEY) } }
    private companion object { const val KEY = "opted_out" }
}

/** Settings reads and saves mirror the push toggles to the device; signing out forgets them. */
class PushMirroringSettings(
    private val inner: SettingsService,
    private val record: (Preferences) -> Unit,
    private val clear: () -> Unit,
) : SettingsService by inner {
    override suspend fun load() = inner.load().also { record(it.preferences) }
    override suspend fun savePreferences(preferences: Preferences) = inner.savePreferences(preferences).also { record(it.preferences) }
    override suspend fun deleteAccount() { inner.deleteAccount(); clear() }
    override suspend fun signOut() { inner.signOut(); clear() }
}

/** A notification ready to show: generic text and where a tap goes. */
data class PushNotice(val category: PushCategory?, val title: String, val body: String, val tap: PushTap?)

object PushNotices {
    private const val DEFAULT_TITLE = "DAD"
    private const val MAX_TEXT = 200

    /**
     * The notification for a message's data, or null when there is nothing to show or the donor opted out of the
     * category. A category the app does not know yet still shows, on the general channel.
     */
    fun from(data: Map<String, String>, optOuts: PushOptOuts): PushNotice? {
        val tap = PushTap.from(data["category"], data["path"]) ?: return null
        val category = PushCategory.of(tap.category)
        if (category != null && !optOuts.allows(category)) return null
        val body = data["body"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = data["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: category?.label ?: DEFAULT_TITLE
        return PushNotice(category, title.take(MAX_TEXT), body.take(MAX_TEXT), tap)
    }
}

/** When to ask for the notification permission: once, after sign-in, only where push can work and it is needed. */
object PushPermissionPolicy {
    const val RUNTIME_PERMISSION_SDK = 33
    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    fun shouldAsk(sdk: Int, granted: Boolean, alreadyAsked: Boolean, pushAvailable: Boolean, signedIn: Boolean): Boolean =
        sdk >= RUNTIME_PERMISSION_SDK && !granted && !alreadyAsked && pushAvailable && signedIn
}

/** Donor push over the donor session (`registerPushDevice` / `unregisterPushDevice`). The server drops the token
 *  itself when the session ends, so sign-out needs no unregister. */
class WireDonorPush(private val product: ProductClient) : PushRegistration {
    override suspend fun register(token: String) {
        product.perform {
            it.create(AccountApi::class.java).registerPushDevice(
                RegisterPushDeviceRequest(RegisterPushDeviceRequest.Platform.ANDROID, token, environment = null),
            ).requireBody()
        }
    }

    override suspend fun unregister() {
        product.perform { it.create(AccountApi::class.java).unregisterPushDevice().requireBody() }
    }
}

/**
 * A tapped notification, from the data the server sends with it: a `category` (`REFERRAL_CONVERTED`,
 * `STREAK_AT_RISK`, ...) and an app `path` to open. Notifications never carry names or amounts.
 */
data class PushTap(val category: String, val path: String?) {
    companion object {
        private val CATEGORY = Regex("^[A-Z][A-Z_]{0,63}$")

        /** The tap in a launch intent's extras, or null when the intent did not come from a notification. */
        fun from(category: String?, path: String?): PushTap? =
            category?.takeIf(CATEGORY::matches)?.let { PushTap(it, path?.takeIf { p -> p.length <= 256 }) }
    }
}
