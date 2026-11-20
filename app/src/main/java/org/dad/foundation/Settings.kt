package org.dad.foundation

/**
 * The account header the settings screen shows. [accountRole] is fixed at creation and immutable
 * (D-1), so it is display-only. The saved time zone controls the streak’s local-day rollover (§10).
 */
data class ProfileSummary(
    val email: String,
    val accountRole: String,
    val zip: String?,
    val city: String?,
    val phone: String?,
    val timezone: String? = null,
    val timezoneOverride: String? = null,
) {
    val zipLabel: UiText get() = zip?.takeIf { it.isNotBlank() }?.let(UiText::plain) ?: UiText.res(R.string.settings_not_set)
}

/**
 * The donor's preference block. It carries the optimistic-concurrency [version] it was read at;
 * saving sends that version so a stale overwrite is rejected server-side. Email toggles, the push
 * category toggles, and the visibility/volunteer switches are all here. A null gift-processed /
 * lapsed toggle is treated as its default so an older server payload still round-trips.
 */
data class Preferences(
    val version: Int,
    val referralEmails: Boolean,
    val marketingEmails: Boolean,
    val pushReferralConverted: Boolean,
    val pushStreakAtRisk: Boolean,
    val pushStreakMilestone: Boolean,
    val pushStreakLapsed: Boolean,
    val pushBadgeEarned: Boolean,
    val pushEventNearby: Boolean,
    val pushEventReminder: Boolean,
    val pushEventPromoted: Boolean,
    val pushGiftProcessed: Boolean,
    val rankingsOptedOut: Boolean,
    val networkVisible: Boolean,
    val volunteer: Boolean,
    /** Free-form event interests picked when opting in as a volunteer (E-1), already trimmed and de-duplicated. */
    val volunteerInterests: List<String> = emptyList(),
) {
    /** A UI-facing toggle: its label, current value, and a copy producing the updated block. */
    data class Toggle(val key: String, val label: UiText, val value: Boolean)

    /** The push-notification category toggles (§6.11), in display order. Payment-failure emails
     *  are deliberately absent — they are non-toggleable server-side. */
    val pushToggles: List<Toggle>
        get() = PushCategory.entries.map { Toggle(it.settingsKey, it.label, pushEnabled(it)) }

    private fun pushEnabled(category: PushCategory): Boolean = when (category) {
        PushCategory.REFERRAL_CONVERTED -> pushReferralConverted
        PushCategory.STREAK_AT_RISK -> pushStreakAtRisk
        PushCategory.STREAK_MILESTONE -> pushStreakMilestone
        PushCategory.STREAK_LAPSED -> pushStreakLapsed
        PushCategory.BADGE_EARNED -> pushBadgeEarned
        PushCategory.EVENT_NEARBY -> pushEventNearby
        PushCategory.EVENT_REMINDER -> pushEventReminder
        PushCategory.EVENT_PROMOTED -> pushEventPromoted
        PushCategory.GIFT_PROCESSED -> pushGiftProcessed
    }

    private fun withPush(category: PushCategory, value: Boolean): Preferences = when (category) {
        PushCategory.REFERRAL_CONVERTED -> copy(pushReferralConverted = value)
        PushCategory.STREAK_AT_RISK -> copy(pushStreakAtRisk = value)
        PushCategory.STREAK_MILESTONE -> copy(pushStreakMilestone = value)
        PushCategory.STREAK_LAPSED -> copy(pushStreakLapsed = value)
        PushCategory.BADGE_EARNED -> copy(pushBadgeEarned = value)
        PushCategory.EVENT_NEARBY -> copy(pushEventNearby = value)
        PushCategory.EVENT_REMINDER -> copy(pushEventReminder = value)
        PushCategory.EVENT_PROMOTED -> copy(pushEventPromoted = value)
        PushCategory.GIFT_PROCESSED -> copy(pushGiftProcessed = value)
    }

    /** The email toggles (§6.11). */
    val emailToggles: List<Toggle>
        get() = listOf(
            Toggle("referral_emails", UiText.res(R.string.settings_referral_emails), referralEmails),
            Toggle("marketing_emails", UiText.res(R.string.settings_marketing_emails), marketingEmails),
        )

    /** The privacy/participation switches. rankingsOptedOut is inverted for display ("Show my
     *  ranking" = NOT opted out) so the UI reads naturally. */
    val showRanking: Boolean get() = !rankingsOptedOut

    /** Produce an updated copy with one toggle flipped by key. Unknown keys return this unchanged. */
    fun with(key: String, value: Boolean): Preferences = when (key) {
        "referral_emails" -> copy(referralEmails = value)
        "marketing_emails" -> copy(marketingEmails = value)
        "rankings_opted_out" -> copy(rankingsOptedOut = value)
        "network_visible" -> copy(networkVisible = value)
        "volunteer" -> copy(volunteer = value)
        else -> PushCategory.entries.firstOrNull { it.settingsKey == key }?.let { withPush(it, value) } ?: this
    }
}

/**
 * The delete-account flow gate. Deletion is irreversible (backend removes personal data, keeps
 * legally-required gift records), so the UI requires an explicit confirm step; the donor must type
 * the exact confirmation word before the action is enabled. Pure so the gate is testable.
 */
data class DeleteAccountGate(val typed: String) {
    companion object { const val CONFIRM_WORD = "DELETE" }

    /** True only when the donor typed the exact confirmation word (case-sensitive). */
    val isConfirmed: Boolean get() = typed == CONFIRM_WORD

    val helpText: UiText
        get() = UiText.res(R.string.settings_delete_account_help, CONFIRM_WORD)
}

/** Why an interest could not be added; the donor sees [message]. */
enum class InterestRejection(val message: UiText) {
    EMPTY(UiText.res(R.string.settings_type_an_interest_to_add)),
    TOO_LONG(UiText.res(R.string.settings_keep_an_interest_to_characters, VolunteerInterests.MAX_LENGTH)),
    DUPLICATE(UiText.res(R.string.settings_that_interest_is_already_on_your)),
    FULL(UiText.res(R.string.settings_you_can_add_up_to_interests, VolunteerInterests.MAX_COUNT)),
}

/**
 * The rules for the free-form interest list, mirroring the contract's `volunteer_interests` limits (at most
 * [MAX_COUNT] entries of 1 to [MAX_LENGTH] characters, unique). Entries are trimmed with inner runs of whitespace
 * collapsed, and two entries that differ only in letter case count as the same interest. Pure so it is unit-tested.
 */
object VolunteerInterests {
    const val MAX_COUNT = 20
    const val MAX_LENGTH = 60

    private val WHITESPACE = Regex("\\s+")

    fun normalize(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    /** The list with [raw] appended, or the reason it cannot be. */
    fun add(current: List<String>, raw: String): Result<List<String>> {
        val interest = normalize(raw)
        val rejection = when {
            interest.isEmpty() -> InterestRejection.EMPTY
            interest.codePointCount(0, interest.length) > MAX_LENGTH -> InterestRejection.TOO_LONG
            current.any { it.equals(interest, ignoreCase = true) } -> InterestRejection.DUPLICATE
            current.size >= MAX_COUNT -> InterestRejection.FULL
            else -> null
        }
        return if (rejection == null) Result.success(current + interest) else Result.failure(InterestRejected(rejection))
    }

    fun remove(current: List<String>, interest: String): List<String> = current.filterNot { it == interest }
}

class InterestRejected(val reason: InterestRejection) : Exception(reason.name)
