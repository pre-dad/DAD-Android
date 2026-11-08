package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

// Donor home reads (PRD S-1..S-4, A-1..A-5, I-1..I-3, §6.1 gift history): the dashboard card set,
// today's initiative, paged gift history, a gift record and a calendar-year summary. Pure value types
// plus two view models; the network sits behind [DashboardService]. Money is int64 USD minor units,
// formatted only by [formatMinorUsd]. Nothing here logs. The Kotlin counterpart of iOS wiring P2.

// MARK: - Dashboard

/** The streak card ladder (S-2/S-3). [bar] has one segment per day of the current window. */
data class HomeLadder(
    val days: Long,
    val lastMilestone: Long,
    val nextMilestone: Long?,
    val daysToNext: Long?,
    val bar: List<Boolean>,
) {
    /** "12 days to your 30-day milestone", or null past the last known milestone. */
    val nextLabel: UiText? get() {
        val next = nextMilestone ?: return null
        val to = daysToNext ?: return null
        return UiText.plural(R.plurals.home_days_to_milestone, to.toQuantity(), to, next)
    }
}

data class HomeStreak(
    val current: Int,
    /** A renewal failed and the donor is inside the 7-day grace period (S-1). */
    val atRisk: Boolean,
    val graceEndsAt: OffsetDateTime?,
    val ladder: HomeLadder?,
) {
    val headline: UiText get() = UiText.plural(R.plurals.home_streak_headline, current, current)
}

/**
 * What the rank line shows. Opted out is the explicit server flag (`rankings_opted_out`), distinct from
 * "opted in but not ranked yet" (null rank with the flag false); neither shows a position or a zero.
 * A ranked standing reuses [RankingStanding], whose label treats `national_percentile` as the top bucket.
 */
sealed interface RankLine {
    data class Ranked(val standing: RankingStanding) : RankLine
    data object NotRankedYet : RankLine
    data object OptedOut : RankLine
}

data class HomeReferrals(val link: Long, val irl: Long, val totalMinor: Long) {
    val joined: Long get() = link + irl
    val totalLabel: String get() = formatMinorUsd(totalMinor)
}

/** The ready dashboard. A null [streak], [referrals] or [network] means the server does not know the figure: never shown as zero. */
data class HomeDashboard(
    val computedAt: OffsetDateTime,
    val foundingDonor: Boolean,
    val streak: HomeStreak?,
    val rank: RankLine,
    val impact: ImpactTotals,
    val network: HomeNetwork?,
    val referrals: HomeReferrals?,
)

data class HomeNetwork(val activeCount: Long, val annualizedMinor: Long) {
    val annualizedLabel: String get() = formatMinorUsd(annualizedMinor)
}

/** `Preparing` is a normal server answer for a brand-new donor whose figures are not computed yet. */
sealed interface DashboardState {
    data object Preparing : DashboardState
    data class Ready(val dashboard: HomeDashboard) : DashboardState
}

// MARK: - Today's initiative

data class HomeMetric(val label: String, val value: Long, val unit: Unit?, val verifiedOn: String) {
    enum class Unit { USD_CENTS, PEOPLE, INITIATIVES, PARTNERS }

    val valueLabel: UiText get() {
        val count = String.format(java.util.Locale.US, "%,d", value)
        val quantity = value.toQuantity()
        return when (unit) {
            Unit.USD_CENTS -> UiText.plain(formatMinorUsd(value))
            Unit.PEOPLE -> UiText.plural(R.plurals.home_metric_people, quantity, count)
            Unit.INITIATIVES -> UiText.plural(R.plurals.home_metric_initiatives, quantity, count)
            Unit.PARTNERS -> UiText.plural(R.plurals.home_metric_partners, quantity, count)
            null -> UiText.plain(count)
        }
    }
}

data class HomeInitiative(
    val id: String,
    val slug: String,
    val title: String,
    val summary: String,
    val location: String,
    val kind: String,
    val startedOn: String,
    val metrics: List<HomeMetric>,
) {
    /** A-2: up to four headline metrics. */
    val headlineMetrics: List<HomeMetric> get() = metrics.take(4)

    companion object {
        /** A-5: display attribution, not designated giving. Counsel owns the final wording. */
        val ATTRIBUTION_NOTE = UiText.res(R.string.home_this_is_display_attribution_not)
    }
}

// MARK: - Gifts

data class HomeGift(
    val id: String,
    val amountMinor: Long,
    val paidAt: OffsetDateTime,
    val monthly: Boolean,
    val refundedMinor: Long,
    val netMinor: Long,
) {
    val amountLabel: String get() = formatMinorUsd(amountMinor)
    val netLabel: String get() = formatMinorUsd(netMinor)
    val refundLabel: UiText? get() = if (refundedMinor > 0) UiText.res(R.string.home_refunded_amount, formatMinorUsd(refundedMinor)) else null
    val frequencyLabel: UiText get() = if (monthly) UiText.res(R.string.home_monthly) else UiText.res(R.string.home_one_time)
}

data class GiftHistoryPage(val gifts: List<HomeGift>, val nextOffset: Int?)

data class HomeRefund(val id: String, val amountMinor: Long, val status: String, val observedAt: OffsetDateTime)

/** A gift record with its refunds. Sandbox records are not tax receipts (G-11); the flag is carried so the screen says so. */
data class HomeReceipt(val gift: HomeGift, val refunds: List<HomeRefund>, val taxReceipt: Boolean, val asOf: OffsetDateTime)

data class AnnualGivingSummary(
    val year: Int,
    val giftCount: Int,
    val amountMinor: Long,
    val refundedMinor: Long,
    val netMinor: Long,
    val taxReceipt: Boolean,
) {
    val netLabel: String get() = formatMinorUsd(netMinor)
}

// MARK: - Service seam

/** The donor-home read seam. [todayInitiative] answers null for a 404 (nothing in rotation today). */
interface DashboardService {
    suspend fun dashboard(): DashboardState
    suspend fun todayInitiative(): HomeInitiative?
    suspend fun gifts(offset: Int): GiftHistoryPage
    suspend fun receipt(giftId: String): HomeReceipt
    suspend fun annualSummary(year: Int): AnnualGivingSummary
}

internal fun homeMessage(error: Throwable): UiText = when (error) {
    is AccountFailure.SignedOut -> UiText.res(R.string.common_please_sign_in_again)
    is AccountFailure.RateLimited -> UiText.res(R.string.common_too_many_requests_please_wait_a)
    else -> UiText.res(R.string.home_we_couldnt_load_this_right_now)
}

// MARK: - Dashboard model

/**
 * Drives the donor home cards. A refresh that fails or is cancelled keeps the cards already on
 * screen (a notice explains a failed one); only a first load with nothing to show becomes [Phase.Failed].
 */
class DashboardModel(private val service: DashboardService) : ViewModel() {
    sealed interface Phase {
        data object Loading : Phase
        data class Ready(val state: DashboardState) : Phase
        data class Failed(val message: UiText) : Phase
    }

    sealed interface InitiativePhase {
        data object Loading : InitiativePhase
        data object None : InitiativePhase
        data class Ready(val initiative: HomeInitiative) : InitiativePhase
        data object Failed : InitiativePhase
    }

    var phase: Phase by mutableStateOf(Phase.Loading)
        private set
    var initiative: InitiativePhase by mutableStateOf(InitiativePhase.Loading)
        private set
    /** Shown above stale cards when a refresh failed. */
    var refreshNotice: UiText? by mutableStateOf(null)
        private set
    /** The session ended (a refresh was refused twice): the shell must route to sign-in. */
    var signedOut: Boolean by mutableStateOf(false)
        private set

    /** Counts loads whose dashboard result landed, so a screen can react to a fresh read. */
    var loadsCompleted: Int by mutableIntStateOf(0)
        private set

    /** Null until a Ready dashboard exists: unknown never reads as "not a founding donor". */
    val foundingDonor: Boolean? get() =
        ((phase as? Phase.Ready)?.state as? DashboardState.Ready)?.dashboard?.foundingDonor

    private var generation = 0
    private var job: Job? = null

    fun load() {
        job?.cancel()
        generation++
        val current = generation
        if (phase !is Phase.Ready) phase = Phase.Loading
        if (initiative !is InitiativePhase.Ready) initiative = InitiativePhase.Loading
        refreshNotice = null
        job = viewModelScope.launch {
            val (dash, today) = coroutineScope {
                val dashboard = async { runCatchingHome { service.dashboard() } }
                val rotation = async { runCatchingHome { service.todayInitiative() } }
                dashboard.await() to rotation.await()
            }
            if (current != generation) return@launch
            dash.fold(
                onSuccess = { phase = Phase.Ready(it) },
                onFailure = { error ->
                    if (error is AccountFailure.SignedOut) signedOut = true
                    if (phase is Phase.Ready) refreshNotice = homeMessage(error) else phase = Phase.Failed(homeMessage(error))
                },
            )
            loadsCompleted++
            today.fold(
                onSuccess = { value -> initiative = if (value == null) InitiativePhase.None else InitiativePhase.Ready(value) },
                onFailure = { error ->
                    if (error is AccountFailure.SignedOut) signedOut = true
                    if (initiative !is InitiativePhase.Ready) initiative = InitiativePhase.Failed
                },
            )
        }
    }

    /**
     * Cancellation is rethrown (the screen left or a newer load replaced this one) so the cards already
     * shown stay exactly as they were; every other failure is returned for the model to present.
     */
    private suspend fun <T> runCatchingHome(block: suspend () -> T): Result<T> =
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }

    companion object {
        fun factory(service: DashboardService): ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardModel(service) }
        }
    }
}

// MARK: - Gift history model

/**
 * Paginated gift history (50 rows a page from the server). Memory is bounded: past [MAX_ROWS] the model
 * stops fetching and says so. A reload or [cancel] invalidates any page in flight.
 */
class GiftHistoryModel(
    private val service: DashboardService,
    private val year: Int? = null,
    private val showPriorYear: Boolean? = null,
) : ViewModel() {
    sealed interface Phase {
        data object Loading : Phase
        data object Ready : Phase
        data class Failed(val message: UiText) : Phase
    }

    sealed interface ReceiptPhase {
        val giftId: String
        data class Loading(override val giftId: String) : ReceiptPhase
        data class Ready(val receipt: HomeReceipt) : ReceiptPhase { override val giftId: String get() = receipt.gift.id }
        data class Failed(override val giftId: String, val message: UiText) : ReceiptPhase
    }

    var phase: Phase by mutableStateOf(Phase.Loading)
        private set
    var gifts: List<HomeGift> by mutableStateOf(emptyList())
        private set
    var nextOffset: Int? by mutableStateOf(null)
        private set
    var isLoadingMore: Boolean by mutableStateOf(false)
        private set
    var moreError: UiText? by mutableStateOf(null)
        private set
    var summary: AnnualGivingSummary? by mutableStateOf(null)
        private set
    /** Only in January: last year's total, which is when donors reach for it (year-end tax summary). */
    var priorSummary: AnnualGivingSummary? by mutableStateOf(null)
        private set
    var receipt: ReceiptPhase? by mutableStateOf(null)
        private set
    var signedOut: Boolean by mutableStateOf(false)
        private set

    private var generation = 0
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var receiptJob: Job? = null

    val hasMore: Boolean get() = nextOffset != null && gifts.size < MAX_ROWS
    val reachedLimit: Boolean get() = nextOffset != null && gifts.size >= MAX_ROWS
    val isEmpty: Boolean get() = phase == Phase.Ready && gifts.isEmpty()

    fun load() {
        loadJob?.cancel(); pageJob?.cancel(); pageJob = null
        generation++
        val current = generation
        isLoadingMore = false
        moreError = null
        val hadRows = gifts.isNotEmpty()
        if (!hadRows) phase = Phase.Loading
        loadJob = viewModelScope.launch {
            try {
                val page = service.gifts(0)
                if (current != generation) return@launch
                gifts = bounded(page.gifts)
                nextOffset = advance(page.nextOffset, past = 0)
                phase = Phase.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current != generation) return@launch
                if (error is AccountFailure.SignedOut) signedOut = true
                if (hadRows) moreError = homeMessage(error) else phase = Phase.Failed(homeMessage(error))
                return@launch
            }
            // The year total is secondary: a failed or missing summary just hides the card.
            val today = java.time.LocalDate.now()
            val summaryYear = year ?: today.year
            val loaded = try { service.annualSummary(summaryYear) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val prior = if (showPriorYear ?: (today.monthValue == 1)) {
                try { service.annualSummary(summaryYear - 1) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            } else null
            if (current == generation) { summary = loaded; priorSummary = prior }
        }
    }

    /** Fetch the next page on a job the model owns, so [cancel] (the screen leaving) stops it. */
    fun requestMore() {
        val offset = nextOffset ?: return
        if (pageJob?.isActive == true || !hasMore) return
        val current = generation
        isLoadingMore = true
        moreError = null
        pageJob = viewModelScope.launch {
            try {
                val page = service.gifts(offset)
                if (current != generation) return@launch
                val known = gifts.mapTo(HashSet()) { it.id }
                gifts = bounded(gifts + page.gifts.filter { it.id !in known })
                nextOffset = advance(page.nextOffset, past = offset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current != generation) return@launch
                if (error is AccountFailure.SignedOut) signedOut = true
                moreError = homeMessage(error)
            } finally {
                if (current == generation) isLoadingMore = false
            }
        }
    }

    /** Stop any page in flight (the screen left); loaded rows stay. */
    fun cancel() {
        loadJob?.cancel(); pageJob?.cancel(); receiptJob?.cancel()
        loadJob = null; pageJob = null; receiptJob = null
        generation++
        isLoadingMore = false
    }

    fun showReceipt(giftId: String) {
        receiptJob?.cancel()
        receipt = ReceiptPhase.Loading(giftId)
        receiptJob = viewModelScope.launch {
            try {
                val value = service.receipt(giftId)
                if (receipt?.giftId == giftId) receipt = ReceiptPhase.Ready(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.SignedOut) signedOut = true
                if (receipt?.giftId == giftId) receipt = ReceiptPhase.Failed(giftId, homeMessage(error))
            }
        }
    }

    fun hideReceipt() { receiptJob?.cancel(); receiptJob = null; receipt = null }

    override fun onCleared() { cancel() }

    private fun bounded(rows: List<HomeGift>): List<HomeGift> = rows.take(MAX_ROWS)

    /** A server offset that does not move forward would loop forever: treat it as the end. */
    private fun advance(next: Int?, past: Int): Int? = next?.takeIf { it > past }

    companion object {
        const val MAX_ROWS = 500
        fun factory(service: DashboardService): ViewModelProvider.Factory = viewModelFactory {
            initializer { GiftHistoryModel(service) }
        }
    }
}

// MARK: - Streak milestones

/** The highest streak milestone the donor has already been shown (or that predates this install). */
interface MilestoneStore {
    fun seen(): Long?
    fun markSeen(milestone: Long)
    fun clear()
}

class PrefsMilestoneStore(context: android.content.Context) : MilestoneStore {
    private val prefs = context.applicationContext.getSharedPreferences("org.dad.milestones", android.content.Context.MODE_PRIVATE)

    override fun seen(): Long? = if (prefs.contains(KEY)) prefs.getLong(KEY, 0) else null
    override fun markSeen(milestone: Long) { prefs.edit { putLong(KEY, milestone) } }
    override fun clear() { prefs.edit { clear() } }

    private companion object { const val KEY = "highest_seen" }
}

class MemoryMilestoneStore(private var value: Long? = null) : MilestoneStore {
    override fun seen(): Long? = value
    override fun markSeen(milestone: Long) { value = milestone }
    override fun clear() { value = null }
}

/**
 * The newly crossed milestone to celebrate, or null. The first call seeds the baseline silently so
 * milestones reached before this feature existed never pop a card; after that only a milestone above
 * the baseline that the current streak still covers counts.
 */
fun pendingMilestone(streak: HomeStreak?, store: MilestoneStore): Long? {
    val last = streak?.ladder?.lastMilestone ?: return null
    val baseline = store.seen()
    if (baseline == null) {
        store.markSeen(last)
        return null
    }
    return last.takeIf { it > baseline && streak.current >= it }
}

fun milestoneLine(days: Long): UiText = when (days) {
    7L -> UiText.res(R.string.home_7_days_a_full_week_of_aid_on_the)
    30L -> UiText.res(R.string.home_30_days_thats_a_month_of_aid_on)
    100L -> UiText.res(R.string.home_100_days_triple_digits_on_the)
    365L -> UiText.res(R.string.home_365_days_a_full_year_of_aid_on_the)
    else -> UiText.plural(R.plurals.home_milestone_other_days, days.toQuantity(), days)
}
