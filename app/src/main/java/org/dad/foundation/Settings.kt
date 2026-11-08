package org.dad.foundation

// Account & settings (PRD §6.11, §6.12). Pure value types and the toggle/grouping rules live
// here so the preferences block, its optimistic version, and the delete-account confirmation gate
// are unit-testable without a network or UI — the same "pure core, seam for I/O" shape as the
// other phases.

// MARK: - Profile summary (read-only header)

/**
 * The account header the settings screen shows. [accountRole] is fixed at creation and immutable
 * (D-1), so it is display-only. [zip] drives the streak's local-day rollover (§10), so it is the
 * one profile field settings lets the donor edit here.
 */
data class ProfileSummary(
    val email: String,
    val accountRole: String,
    val zip: String?,
    val city: String?,
    val phone: String?,
) {
    val zipLabel: UiText get() = zip?.takeIf { it.isNotBlank() }?.let(UiText::plain) ?: UiText.res(R.string.settings_not_set)
}

// MARK: - Notification + visibility preferences (§6.11)

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
        get() = listOf(
            Toggle("push_referral_converted", UiText.res(R.string.common_someone_joined_through_you), pushReferralConverted),
            Toggle("push_streak_at_risk", UiText.res(R.string.common_streak_at_risk), pushStreakAtRisk),
            Toggle("push_streak_milestone", UiText.res(R.string.common_streak_milestone), pushStreakMilestone),
            Toggle("push_streak_lapsed", UiText.res(R.string.common_streak_lapsed), pushStreakLapsed),
            Toggle("push_badge_earned", UiText.res(R.string.common_badge_earned), pushBadgeEarned),
            Toggle("push_event_nearby", UiText.res(R.string.common_events_nearby), pushEventNearby),
            Toggle("push_event_reminder", UiText.res(R.string.common_event_reminders), pushEventReminder),
            Toggle("push_event_promoted", UiText.res(R.string.common_event_promoted), pushEventPromoted),
            Toggle("push_gift_processed", UiText.res(R.string.common_gift_processed), pushGiftProcessed),
        )

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
        "push_referral_converted" -> copy(pushReferralConverted = value)
        "push_streak_at_risk" -> copy(pushStreakAtRisk = value)
        "push_streak_milestone" -> copy(pushStreakMilestone = value)
        "push_streak_lapsed" -> copy(pushStreakLapsed = value)
        "push_badge_earned" -> copy(pushBadgeEarned = value)
        "push_event_nearby" -> copy(pushEventNearby = value)
        "push_event_reminder" -> copy(pushEventReminder = value)
        "push_event_promoted" -> copy(pushEventPromoted = value)
        "push_gift_processed" -> copy(pushGiftProcessed = value)
        "rankings_opted_out" -> copy(rankingsOptedOut = value)
        "network_visible" -> copy(networkVisible = value)
        "volunteer" -> copy(volunteer = value)
        else -> this
    }
}

// MARK: - Delete account (store-mandated, §6.12)

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

// MARK: - Volunteer interests (E-1)

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
