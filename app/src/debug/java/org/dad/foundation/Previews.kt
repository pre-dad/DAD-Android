package org.dad.foundation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.OffsetDateTime
import java.util.Date
import kotlinx.coroutines.delay

/**
 * In-memory engagement service for DEBUG previews. One known snapshot: a 5-person network with 2 anonymous, the
 * 24-badge Appendix A catalogue with 7 earned, an opted-in ranking that the toggle flips, and a donor who starts
 * with no public name (choosing one mints the invite).
 */
class PreviewEngagementService : EngagementService {
    private var optedIn = true
    private var publicName: String? = null

    private fun ranking() = if (optedIn) RankingStanding(2, "Austin", 4) else RankingStanding.optedOut()

    override suspend fun snapshot(): EngagementSnapshot = EngagementSnapshot(
        impact = ImpactTotals(givenMinor = 124_000, initiativesFunded = 3, eventsVolunteered = 2),
        badges = BadgeGrid(previewBadges()),
        ranking = ranking(),
        network = NetworkView(
            activeCount = 5,
            annualizedMinor = 348_000,
            nextMilestone = NetworkMilestone(label = "Brought 12 In", target = 12, remaining = 7),
            invitees = listOf(NetworkInvitee("Alex C.", active = true), NetworkInvitee("Jordan M.", active = true), NetworkInvitee("Sam R.", active = false)),
            anonymousActive = 2,
        ),
        referral = publicName?.let {
            ReferralInfo("preview", 2, 1, listOf(Conversion(OffsetDateTime.parse("2026-10-20T12:00:00Z"), "LINK")))
        },
        needsPublicName = publicName == null,
    )

    override suspend fun setRankingOptIn(optIn: Boolean): RankingStanding {
        optedIn = optIn
        return ranking()
    }

    override suspend fun choosePublicName(name: String) { publicName = name }

    override suspend fun initiativeSpend(id: String) = InitiativeSpend(
        totalMinor = 500_000,
        categories = listOf(SpendCategory("Food", 300_000), SpendCategory("Housing", 200_000)),
    )

    private fun previewBadges(): List<Badge> {
        val earnedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z")
        // Codes are the server catalogue's (DAD-Main badges.go); titles, rules and perks are verbatim from PRD Appendix A (approved 2026-10-23); "—" perks are blank.
        val specs = listOf(
            BadgeSpec("F", "Founding Donor", "Started a monthly gift before January 2027, or joined the website waitlist", "FOUNDING_EVENTS"),
            BadgeSpec("1D", "First Day", "Completed a first gift", ""),
            BadgeSpec("7", "One Week", "7-day streak", ""),
            BadgeSpec("30", "One Month", "30-day streak", ""),
            BadgeSpec("100", "Hundred", "100-day streak", ""),
            BadgeSpec("Y1", "One Year", "365-day streak", ""),
            BadgeSpec("1K", "1,000 Days", "1,000-day streak", "MILESTONE_EVENTS"),
            BadgeSpec("Y3", "Three Years", "1,095-day streak", ""),
            BadgeSpec("CB", "Comeback", "Restarted giving after a lapse and reached 30 days", ""),
            BadgeSpec("UP", "Level Up", "Raised their daily amount", ""),
            BadgeSpec("REF1", "First Referral", "First person they invited completed a gift", ""),
            BadgeSpec("REF3", "Brought 3 In", "3 converted referrals", ""),
            BadgeSpec("REF12", "Brought 12 In", "12 converted referrals", ""),
            BadgeSpec("TC", "Team Captain", "20 converted referrals", "TEAM_CAPTAIN"),
            BadgeSpec("IRL", "IRL", "Someone joined by scanning their IRL code", ""),
            BadgeSpec("V", "Volunteer", "Opted in as a volunteer", ""),
            BadgeSpec("SU", "Showed Up", "First event check-in", ""),
            BadgeSpec("V3", "Volunteer ×3", "3 event check-ins", ""),
            BadgeSpec("V10", "Volunteer ×10", "10 event check-ins", ""),
            BadgeSpec("INIT7", "7 Initiatives", "Funded 7 distinct initiatives", ""),
            BadgeSpec("CITY50", "Top 50 City", "Ranked top 50 in their city", ""),
            BadgeSpec("NAT2", "Top 2%", "National top 2% by streak", ""),
            BadgeSpec("TP", "Team Player", "Joined a company team", ""),
            BadgeSpec("WT", "Winning Team", "Their team finished a month at #1 on the company leaderboard", ""),
        )
        return specs.mapIndexed { index, (code, title, rule, perkKey) ->
            val earned = index < 7
            Badge(code, title, rule, Badge.perkCopy(perkKey), earned, if (earned) earnedAt else null)
        }
    }

    private data class BadgeSpec(val code: String, val title: String, val rule: String, val perkKey: String)
}

/** Accepts any partner link, so the DEBUG preview's partner sign-in runs with no backend. */
object PreviewPartnerSignIn : PartnerSignInService {
    override suspend fun requestSignInLink(email: String) {}
    override suspend fun accept(token: String) {}
}

/** In-memory partner service for previews/UITests: an admin or viewer portal. */
class PreviewPartnerService(private val role: PartnerRole = PartnerRole.ADMIN) : PartnerService {
    private val userList = mutableListOf(
        PartnerUser("u1", "ada@acme.com", "Ada Admin", PartnerRole.ADMIN),
        PartnerUser("u2", "val@acme.com", "Val Viewer", PartnerRole.VIEWER),
    )
    private var organization = "Acme Corp"
    private var about = "Doing good together."
    private var workforce = 240
    private var savedLogo: ByteArray? = null

    override suspend fun overview(): PartnerOverview = PartnerOverview(
        organization = organization, description = about, role = role, companyCode = "ACME",
        committed = VerifiedMoney(5_000_000, verified = true),
        deployed = VerifiedMoney(3_000_000, verified = false), // unverified -> hidden (§7.3)
        deployedPercent = 60,
        peopleReached = VerifiedCount(12_400, verified = true),
        employeesEnrolled = 86, workforceSize = workforce, enrolledPercent = 35,
        updatedAt = Date(1_793_000_000_000), hasLogo = savedLogo != null,
        userId = if (role == PartnerRole.ADMIN) "u1" else "u2",
    )

    override suspend fun initiatives(): List<PartnerInitiative> = listOf(
        PartnerInitiative("i1", "Clean Water West", "Wells across 6 counties.", InitiativeStatus.ACTIVE,
            "West", 2_000_000, 1_200_000,
            listOf(PartnerMetric("People reached", 8400, PartnerMetric.Unit.PEOPLE),
                   PartnerMetric("Wells", 32, PartnerMetric.Unit.INITIATIVES))),
    )

    override suspend fun spend(): List<PartnerSpendCategory> = listOf(
        PartnerSpendCategory("Water", 1_800_000), PartnerSpendCategory("Housing", 1_200_000),
    )

    override suspend fun people(): PartnerPeople =
        PartnerPeople(86, workforce, 940_000, 470_000, 42, 6_300, 7, suppressed = false)

    override suspend fun teams(): List<TeamStanding> = listOf(TeamStanding("t1", "Engineering", 31, 1_200_000, 40, 1_200))
    override suspend fun users(): List<PartnerUser> = userList.toList()
    override suspend fun inviteUser(email: String, name: String, role: PartnerRole) {
        userList.add(PartnerUser("u${userList.size + 1}", email, name, role))
    }
    override suspend fun removeUser(id: String) { userList.removeAll { it.id == id } }
    override suspend fun saveProfile(organization: String, description: String) { this.organization = organization; about = description }
    override suspend fun saveWorkforce(size: Int) { workforce = size }
    override suspend fun logo(): ByteArray? = savedLogo
    override suspend fun saveLogo(png: ByteArray?) { savedLogo = png }
    override suspend fun exports(): List<PartnerExport> = emptyList()
    override suspend fun report(): ByteArray = "%PDF-1.4 preview".toByteArray()
    override suspend fun retainedReport(id: String): ByteArray = throw AccountFailure.NotFound
    override suspend fun signOut() {}
    override suspend fun registerPushDevice(token: String) {}
    override suspend fun unregisterPushDevice() {}
}

/**
 * In-memory events service for previews: one reservable public event, one full event (waitlist), one
 * invite-only event and one already under way, exercising the real [EventsModel] + screens.
 */
class PreviewEventsService : EventsService {
    private val soon = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
    private val store = linkedMapOf(
        "e1" to DonorEvent("e1", "park-cleanup", "Park Cleanup", "volunteer", "Zilker Park", "Austin",
            soon, Date(soon.time + 7_200_000), capacity = 50, spotsLeft = 8, status = ReservationStatus.NONE,
            reportIssueUrl = "mailto:help@dad.org", timeZone = "America/Chicago", details = "Bring gloves and water.",
            address = "2100 Barton Springs Rd"),
        "e2" to DonorEvent("e2", "food-bank", "Food Bank Night", "volunteer", "Central Kitchen", "Austin",
            Date(soon.time + 86_400_000), Date(soon.time + 93_600_000), capacity = 30, spotsLeft = 0,
            status = ReservationStatus.NONE, reportIssueUrl = "mailto:help@dad.org", timeZone = "America/Chicago"),
        "e3" to DonorEvent("e3", "founders-dinner", "Founders Dinner", "invite_only", "The Loft", "Austin",
            Date(soon.time + 2 * 86_400_000L), Date(soon.time + 2 * 86_400_000L + 7_200_000), capacity = 20,
            spotsLeft = 5, status = ReservationStatus.NONE, invitationNote = "Founding donors only",
            reportIssueUrl = "mailto:help@dad.org", timeZone = "America/Chicago"),
    )

    override suspend fun events(): List<DonorEvent> = store.values.sortedBy { it.startsAt }
    override suspend fun event(id: String): DonorEvent = store[id] ?: throw AccountFailure.NotFound

    override suspend fun reserve(eventId: String): ReservationOutcome {
        val e = store.getValue(eventId)
        val outcome = if (e.spotsLeft > 0) ReservationOutcome.RESERVED else ReservationOutcome.WAITLISTED
        store[eventId] = e.copy(
            spotsLeft = maxOf(0, e.spotsLeft - 1),
            status = if (outcome == ReservationOutcome.RESERVED) ReservationStatus.RESERVED else ReservationStatus.WAITLISTED,
        )
        return outcome
    }

    override suspend fun cancel(eventId: String) {
        val e = store.getValue(eventId)
        store[eventId] = e.copy(spotsLeft = e.spotsLeft + 1, status = ReservationStatus.NONE)
    }

    override suspend fun checkIn(code: String): CheckInResultValue =
        CheckInResultValue(eventId = "e1", checkedIn = true, alreadyCheckedIn = false)

    override suspend fun calendar(eventId: String): ByteArray =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n".toByteArray()
}

/**
 * In-app preview/test double for [GivingService]: starts with an active $1/day gift so the gift and
 * cancel surfaces both render without a network.
 */
class PreviewGivingService : GivingService {
    private var state: RecurringState =
        RecurringState.Active("sub_preview", GiftAmount.CENTS_PER_DAILY_DOLLAR, Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
    private var applied = false

    override suspend fun recurring(): RecurringState = state
    override suspend fun amountOffer(): AmountOffer? = null
    override suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState {
        val periodEnd = (state as? RecurringState.Active)?.periodEnd ?: Date()
        return RecurringState.Active(subscriptionId, amountMinor, periodEnd).also { state = it }
    }
    override suspend fun cancel(subscriptionId: String, periodEnd: Date): RecurringState {
        val amount = state.amountMinorOrNull ?: GiftAmount.CENTS_PER_DAILY_DOLLAR
        return RecurringState.Ending(subscriptionId, amount, periodEnd, Date(periodEnd.time)).also { state = it }
    }
    override suspend fun paymentMethod(): PaymentMethodState =
        PaymentMethodState(if (applied) PaymentMethodState.Kind.APPLIED else PaymentMethodState.Kind.NONE)
    override suspend fun beginPayment(): PaymentPath = PaymentPath.WebCheckoutHandoff("https://pay.example/setup")
    override suspend fun startGift(amountMinor: Int): PaymentPath = PaymentPath.WebCheckoutHandoff("https://pay.example/checkout")
}

/** In-memory settings service for previews and Compose UI tests. */
class PreviewSettingsService : SettingsService {
    private var prefs = Preferences(
        version = 3, referralEmails = true, marketingEmails = false,
        pushReferralConverted = true, pushStreakAtRisk = true, pushStreakMilestone = true,
        pushStreakLapsed = true, pushBadgeEarned = true, pushEventNearby = false,
        pushEventReminder = true, pushEventPromoted = false, pushGiftProcessed = true,
        rankingsOptedOut = false, networkVisible = true, volunteer = true,
    )

    override suspend fun load(): SettingsSnapshot = SettingsSnapshot(
        profile = ProfileSummary(email = "donor@example.com", accountRole = "DONOR",
            zip = "78701", city = "Austin", phone = null),
        preferences = prefs,
    )

    override suspend fun savePreferences(preferences: Preferences): SettingsSnapshot {
        prefs = preferences.copy(version = preferences.version + 1) // server bumps the version
        return load()
    }

    override suspend fun deleteAccount() {}
    override suspend fun signOut() {}
}

class PreviewStore(signedIn: Boolean = true) : CredentialStore {
    private var value: Credentials? = if (signedIn) Credentials("preview", "preview-refresh", Long.MAX_VALUE) else null
    override fun load() = value
    override fun save(value: Credentials) { this.value = value }
    override fun clear() { value = null }
}
class PreviewAPI(private var failFirst: Boolean) : AccountAPI {
    override suspend fun profile(access: String): AccountProfile {
        delay(250)
        if (failFirst) { failFirst = false; throw AccountFailure.Unavailable }
        return AccountProfile("alex@example.org", "Alex")
    }
    override suspend fun refresh(token: String): Credentials = throw AccountFailure.SignedOut
    override suspend fun claim(token: String): Credentials = throw AccountFailure.Unavailable
    override suspend fun logout(access: String) = Unit
}
/** DEBUG preview sign-in (`preview_signed_out`): accepts code 123456, never touches a network. */
class PreviewSignIn : SignInService {
    override suspend fun requestPhoneCode(phone: String): String = phone
    override suspend fun verifyPhone(handle: String, code: String): Credentials =
        if (code == "123456") Credentials("preview", "preview-refresh", Long.MAX_VALUE) else throw AccountFailure.SignedOut
}
/** Preview settings tied to the live (preview) session, so signing out or deleting from the donor
 *  shell really ends the session and routes back to sign-in, like a release build. */
class PreviewSessionSettings(private val session: AccountSession) : SettingsService {
    private val inner = PreviewSettingsService()
    override suspend fun load() = inner.load()
    override suspend fun savePreferences(preferences: Preferences) = inner.savePreferences(preferences)
    override suspend fun deleteAccount() { session.discardLocal() }
    override suspend fun signOut() { session.logout() }
}


fun EngagementModel.Companion.previewFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { EngagementModel(PreviewEngagementService()) }
}

fun PartnerModel.Companion.previewFactory(role: PartnerRole = PartnerRole.ADMIN): ViewModelProvider.Factory =
    viewModelFactory { initializer { PartnerModel(PreviewPartnerService(role)) } }

fun EventsModel.Companion.previewFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { EventsModel(PreviewEventsService()) }
}

fun GivingModel.Companion.previewFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { GivingModel(PreviewGivingService(), pollDelayMs = 100) }
}

fun SettingsModel.Companion.previewFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsModel(PreviewSettingsService()) }
}
