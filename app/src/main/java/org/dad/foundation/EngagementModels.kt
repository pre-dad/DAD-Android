package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Engagement view models + the service seam (PRD A-3, I-*, K-*, N-*, R-*, B-*). The models own an
// explicit load/ready/failed state, so impact, badges, rankings, network and the invite are
// unit-tested without a network or UI. The Kotlin counterpart of the iOS wiring-P5 model.

/**
 * Everything the engagement screen shows. Each section is null when the server did not give it (the
 * read failed, or the figure is not computed yet): the screen says "can't be shown right now" for that
 * section instead of drawing a zero, an empty grid or a "not ranked" claim it cannot back.
 */
data class EngagementSnapshot(
    val impact: ImpactTotals?,
    val badges: BadgeGrid?,
    val ranking: RankingStanding?,
    val network: NetworkView?,
    /** Null when there is no usable invite code; see [needsPublicName] for the one case that is the donor's to fix. */
    val referral: ReferralInfo?,
    /** The donor has not chosen a public name yet, so the server issues no invite link. */
    val needsPublicName: Boolean = false,
) {
    fun replacing(ranking: RankingStanding?) = copy(ranking = ranking)
}

/**
 * The seam the engagement model calls. A conformer maps to the wire (WireEngagement) or to a
 * preview/test double; all value types so the models stay testable.
 */
interface EngagementService {
    suspend fun snapshot(): EngagementSnapshot

    /** Opt into or out of the public rankings (K-2). Opting out yields [RankingStanding.optedOut]. */
    suspend fun setRankingOptIn(optIn: Boolean): RankingStanding

    /** Choose the public name the invite link and IRL code are issued for (R-1). Throws [AccountFailure.InvalidInput] for a name the server refuses. */
    suspend fun choosePublicName(name: String)

    /** Spend by category for one initiative (A-3); totals only. */
    suspend fun initiativeSpend(id: String): InitiativeSpend
}

/** Explicit phase so the UI never has to infer "is the load in flight". */
sealed interface EngagementPhase {
    data object Loading : EngagementPhase
    data object Ready : EngagementPhase
    data class Failed(val message: UiText) : EngagementPhase
}

/**
 * Drives the engagement screen. One load fetches the snapshot; a refresh that fails or is cancelled keeps
 * the content already on screen. The rankings toggle flips at once ([displayedRanking]) and reverts with an
 * inline notice if the save fails.
 */
class EngagementModel(private val service: EngagementService) : ViewModel() {
    var phase: EngagementPhase by mutableStateOf(EngagementPhase.Loading)
        private set
    var snapshot: EngagementSnapshot? by mutableStateOf(null)
        private set
    /** A failed ranking toggle shows inline; the loaded screen and the prior standing stay put. */
    var actionError: UiText? by mutableStateOf(null)
        private set
    /** Shown above stale content when a refresh failed. */
    var refreshNotice: UiText? by mutableStateOf(null)
        private set
    /** The session ended (a refresh was refused twice): the shell must route to sign-in. */
    var signedOut: Boolean by mutableStateOf(false)
        private set
    /** The rankings choice being saved; clearing it reverts the card to the standing the server last confirmed. */
    var pendingOptIn: Boolean? by mutableStateOf(null)
        private set
    var nameError: UiText? by mutableStateOf(null)
        private set
    var isSavingName: Boolean by mutableStateOf(false)
        private set

    private var generation = 0
    private var job: Job? = null

    /** What the rankings card shows right now: the confirmed standing, or the donor's pending choice. */
    val displayedRanking: RankingStanding?
        get() {
            val ranking = snapshot?.ranking ?: return null
            return when (pendingOptIn) {
                null -> ranking
                false -> RankingStanding.optedOut()
                true -> RankingStanding(ranking.nationalPercentile, ranking.city, ranking.cityRank, optedOut = false)
            }
        }

    fun load() {
        job?.cancel()
        generation++
        val current = generation
        val prior = phase
        refreshNotice = null
        if (snapshot == null) phase = EngagementPhase.Loading
        job = viewModelScope.launch {
            try {
                val value = service.snapshot()
                if (current != generation) return@launch
                snapshot = value
                phase = EngagementPhase.Ready
            } catch (cancelled: CancellationException) {
                if (current == generation) phase = prior
                throw cancelled
            } catch (error: Exception) {
                if (current != generation) return@launch
                if (error is AccountFailure.SignedOut) signedOut = true
                if (snapshot != null) { phase = EngagementPhase.Ready; refreshNotice = homeMessage(error) }
                else phase = EngagementPhase.Failed(homeMessage(error))
            }
        }
    }

    /** Toggle rankings visibility (K-2). A second tap while one is saving is ignored. */
    fun setRankingOptIn(optIn: Boolean) {
        if (snapshot == null || pendingOptIn != null) return
        actionError = null
        pendingOptIn = optIn
        viewModelScope.launch {
            try {
                val ranking = service.setRankingOptIn(optIn)
                snapshot = snapshot?.replacing(ranking)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.SignedOut) signedOut = true
                actionError = homeMessage(error)
            } finally {
                pendingOptIn = null
            }
        }
    }

    /** Choose the public name behind the invite link (R-1), then reload so the code and link appear. */
    fun choosePublicName(raw: String) {
        val name = raw.trim()
        if (isSavingName) return
        if (name.length !in 1..MAX_PUBLIC_NAME) { nameError = UiText.res(R.string.eng_enter_a_name_between_1_and, MAX_PUBLIC_NAME); return }
        isSavingName = true
        nameError = null
        viewModelScope.launch {
            try {
                service.choosePublicName(name)
            } catch (cancelled: CancellationException) {
                isSavingName = false
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.SignedOut) signedOut = true
                nameError = when (error) {
                    is AccountFailure.InvalidInput -> UiText.res(R.string.eng_that_name_cant_be_used_try_another)
                    is AccountFailure.Forbidden -> UiText.res(R.string.eng_invite_links_arent_available_for)
                    else -> homeMessage(error)
                }
                isSavingName = false
                return@launch
            }
            isSavingName = false
            load()
        }
    }

    companion object {
        fun factory(service: EngagementService): ViewModelProvider.Factory = viewModelFactory {
            initializer { EngagementModel(service) }
        }
    }
}

/** Spend by category for one initiative (A-3), opened from today's initiative card. Totals only. */
class InitiativeSpendModel(private val service: EngagementService, private val initiativeId: String) : ViewModel() {
    sealed interface Phase {
        data object Loading : Phase
        data class Ready(val spend: InitiativeSpend) : Phase
        /** The initiative has no published spend (404). */
        data object Unpublished : Phase
        data class Failed(val message: UiText) : Phase
    }

    var phase: Phase by mutableStateOf(Phase.Loading)
        private set
    var signedOut: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null

    fun load() {
        job?.cancel()
        val prior = phase
        if (prior !is Phase.Ready) phase = Phase.Loading
        job = viewModelScope.launch {
            try {
                phase = Phase.Ready(service.initiativeSpend(initiativeId))
            } catch (cancelled: CancellationException) {
                phase = prior
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.NotFound) { phase = Phase.Unpublished; return@launch }
                if (error is AccountFailure.SignedOut) signedOut = true
                if (prior !is Phase.Ready) phase = Phase.Failed(homeMessage(error))
            }
        }
    }

    companion object {
        fun factory(service: EngagementService, initiativeId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { InitiativeSpendModel(service, initiativeId) }
        }
    }
}
