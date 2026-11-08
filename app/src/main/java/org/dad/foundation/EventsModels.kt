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
import java.util.Date

// Events view model + the service seam. The model owns explicit load/ready/failed state and the
// reserve / cancel / check-in / calendar transitions, so they are unit-tested without a network, a
// camera, or UI. The server is always authoritative for reservation and check-in; the model only
// pre-checks what the screen should offer and says why a refusal happened.

/** Domain mirror of the contract's CheckInResult so the model/tests never import transport. */
data class CheckInResultValue(val eventId: String, val checkedIn: Boolean, val alreadyCheckedIn: Boolean)

/**
 * The seam the events model calls. A conformer maps to the server ([WireEvents]) or a preview/test
 * double; all value types so the model stays testable. Calls can throw [AccountFailure]
 * (401/403/404/409/422/429) and the model surfaces that.
 */
interface EventsService {
    suspend fun events(): List<DonorEvent>
    suspend fun event(id: String): DonorEvent
    /** The donor's place after the call, or null for a status this build does not know (the caller re-reads). */
    suspend fun reserve(eventId: String): ReservationOutcome?
    suspend fun cancel(eventId: String)
    suspend fun checkIn(code: String): CheckInResultValue
    /** The authenticated `.ics` for one event (E-6). */
    suspend fun calendar(eventId: String): ByteArray
}

sealed interface EventsPhase {
    data object Loading : EventsPhase
    data object Ready : EventsPhase
    data class Failed(val message: UiText) : EventsPhase
}

/** The check-in attempt on the open event (E-8). */
sealed interface CheckInState {
    data object Idle : CheckInState
    data object Checking : CheckInState
    data class Done(val alreadyCheckedIn: Boolean) : CheckInState
    data class Failed(val message: UiText) : CheckInState
}

/** The add-to-calendar fetch (E-6): the screen hands [Ready] to the share sheet, then calls [EventsModel.calendarShared]. */
sealed interface CalendarState {
    data object Idle : CalendarState
    data class Fetching(val eventId: String) : CalendarState
    data class Ready(val eventId: String, val title: String, val bytes: ByteArray) : CalendarState
    data class Failed(val message: UiText) : CalendarState
}

/** What the events screens say for each refusal. Never a server body: those are not user copy. */
internal object EventsCopy {
    val SIGNED_OUT = UiText.res(R.string.common_please_sign_in_again)
    val NOT_ELIGIBLE = UiText.res(R.string.events_youre_not_eligible_for_this_event)
    val GONE = UiText.res(R.string.events_this_event_is_no_longer_available)
    val CHANGED = UiText.res(R.string.events_this_event_changed_while_you_were)
    val TOO_MANY = UiText.res(R.string.common_too_many_attempts_please_wait_a)
    val GENERIC = UiText.res(R.string.common_something_went_wrong_please_try)
    val BAD_SHAPE = UiText.res(R.string.events_that_doesnt_look_like_an_event)
    val CODE_REFUSED = UiText.res(R.string.events_that_code_didnt_work_check_that)
    val WRONG_EVENT = UiText.res(R.string.events_that_code_is_for_a_different_event)
    val NOT_HELD = UiText.res(R.string.events_check_in_is_for_donors_who_hold_a)

    fun general(error: Exception): UiText = when (error) {
        is AccountFailure.SignedOut -> SIGNED_OUT
        is AccountFailure.Forbidden -> NOT_ELIGIBLE
        is AccountFailure.NotFound -> GONE
        is AccountFailure.Conflict -> CHANGED
        is AccountFailure.RateLimited -> TOO_MANY
        else -> GENERIC
    }
}

/**
 * Drives the events list, the event detail and the commands on it. One load fetches the list; reserving,
 * cancelling and checking in re-read the affected event so list and detail re-render without a full reload.
 * [clock] is injected so window decisions are deterministic in tests.
 */
class EventsModel(private val service: EventsService, private val clock: () -> Date = { Date() }) : ViewModel() {
    var phase: EventsPhase by mutableStateOf(EventsPhase.Loading)
        private set
    var events: List<DonorEvent> by mutableStateOf(emptyList())
        private set
    /** The last reserve outcome, so the detail screen can present the confirmation (E-4). */
    var lastOutcome: ReservationOutcome? by mutableStateOf(null)
        private set
    /** A transient action error (reserve/cancel refusal etc.) shown inline without losing the list. */
    var actionError: UiText? by mutableStateOf(null)
        private set
    var checkIn: CheckInState by mutableStateOf(CheckInState.Idle)
        private set
    var calendar: CalendarState by mutableStateOf(CalendarState.Idle)
        private set
    /** Event ids with a command in flight, so the screen disables its buttons and a double tap cannot book twice. */
    var busy: Set<String> by mutableStateOf(emptySet())
        private set
    /** True once the server has refused the session; the host returns to sign-in. */
    var signedOut: Boolean by mutableStateOf(false)
        private set
    private var checkInFlight = false
    private var reloading = false

    fun now(): Date = clock()

    private var loading: Job? = null

    fun load() {
        if (loading?.isActive == true) return
        phase = EventsPhase.Loading
        loading = viewModelScope.launch {
            try {
                events = service.events().sortedBy { it.startsAt } // soonest-first (E-2)
                phase = EventsPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                note(error)
                phase = EventsPhase.Failed(EventsCopy.general(error))
            }
        }
    }

    /** Re-read the list without the loading state (after a conflict); a failed re-read keeps what is on screen. */
    private suspend fun reload() {
        if (reloading) return
        reloading = true
        try {
            events = service.events().sortedBy { it.startsAt }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            note(error)
        } finally {
            reloading = false
        }
    }

    /** Opening an event re-reads it so the detail shows current spots and check-in state (E-3). */
    fun open(eventId: String) {
        viewModelScope.launch { refresh(eventId) }
    }

    fun reserve(eventId: String) = perform(eventId) {
        lastOutcome = service.reserve(eventId)
        refresh(eventId)
    }

    fun cancel(eventId: String) = perform(eventId) {
        service.cancel(eventId)
        lastOutcome = null
        refresh(eventId)
    }

    /** Runs one reserve/cancel at a time per event so a double tap cannot book or cancel twice. */
    private fun perform(eventId: String, action: suspend () -> Unit) {
        if (eventId in busy) return
        busy = busy + eventId
        actionError = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                note(error)
                actionError = EventsCopy.general(error)
                // The picture on screen is stale (full, started, gone): bring in the truth.
                if (error is AccountFailure.Conflict || error is AccountFailure.NotFound) reload()
            } finally {
                busy = busy - eventId
            }
        }
    }

    fun clearOutcome() { lastOutcome = null }
    fun clearActionError() { actionError = null }

    /**
     * Check in with the code the donor typed or scanned (E-8). The code's shape and the event's state are checked
     * first so a request bound to fail is never sent; the server still decides.
     */
    fun checkIn(event: DonorEvent, rawCode: String) {
        if (checkInFlight) return
        val code = rawCode.trim()
        if (!CheckInCode.isWellFormed(code)) { checkIn = CheckInState.Failed(EventsCopy.BAD_SHAPE); return }
        if (event.status == ReservationStatus.WAITLISTED) { checkIn = CheckInState.Failed(EventsCopy.NOT_HELD); return }
        if (!event.canCheckIn(clock())) { checkIn = CheckInState.Failed(notOpenReason(event)); return }
        checkInFlight = true
        checkIn = CheckInState.Checking
        viewModelScope.launch {
            try {
                val result = service.checkIn(code)
                if (!result.eventId.equals(event.id, ignoreCase = true)) {
                    // A real code, but for another event: nothing was checked in here.
                    checkIn = CheckInState.Failed(EventsCopy.WRONG_EVENT)
                } else {
                    checkIn = CheckInState.Done(result.alreadyCheckedIn)
                    refresh(event.id)
                }
            } catch (error: CancellationException) {
                checkIn = CheckInState.Idle
                throw error
            } catch (error: Exception) {
                note(error)
                checkIn = CheckInState.Failed(if (error is AccountFailure.InvalidInput) EventsCopy.CODE_REFUSED else EventsCopy.general(error))
            } finally {
                checkInFlight = false
            }
        }
    }

    /** A photo that held no readable QR code. */
    fun resetCheckIn() { if (!checkInFlight) checkIn = CheckInState.Idle }

    private fun notOpenReason(event: DonorEvent): UiText = when {
        event.cancelled -> UiText.res(R.string.events_this_event_was_cancelled)
        event.checkedInAt != null -> UiText.res(R.string.events_youre_already_checked_in)
        event.status != ReservationStatus.RESERVED -> UiText.res(R.string.events_you_need_a_reserved_seat_to_check)
        else -> when (event.checkInWindow.state(clock())) {
            is CheckInWindow.State.TooEarly -> UiText.res(R.string.events_check_in_opens_when_the_event)
            CheckInWindow.State.TooLate -> UiText.res(R.string.events_check_in_for_this_event_has_closed)
            CheckInWindow.State.Open -> EventsCopy.GENERIC
        }
    }

    /** Fetch the authenticated `.ics` for the screen to hand to the share sheet (E-6). */
    fun addToCalendar(event: DonorEvent) {
        if (calendar is CalendarState.Fetching) return
        calendar = CalendarState.Fetching(event.id)
        viewModelScope.launch {
            try {
                calendar = CalendarState.Ready(event.id, event.title, service.calendar(event.id))
            } catch (error: CancellationException) {
                calendar = CalendarState.Idle
                throw error
            } catch (error: Exception) {
                note(error)
                calendar = CalendarState.Failed(EventsCopy.general(error))
            }
        }
    }

    fun calendarShared() { calendar = CalendarState.Idle }
    fun calendarUnavailable() { calendar = CalendarState.Failed(UiText.res(R.string.events_theres_no_app_on_this_device_that)) }

    private suspend fun refresh(eventId: String) {
        // The action already succeeded; a failed re-read just leaves the row as it was until the next load.
        val updated = try {
            service.event(eventId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AccountFailure.NotFound) {
            events = events.filterNot { it.id == eventId }
            return
        } catch (error: Exception) {
            note(error)
            return
        }
        events = if (events.any { it.id == eventId }) events.map { if (it.id == eventId) updated else it } else (events + updated).sortedBy { it.startsAt }
    }

    private fun note(error: Exception) { if (error is AccountFailure.SignedOut) signedOut = true }

    companion object {
        fun factory(service: EventsService): ViewModelProvider.Factory = viewModelFactory {
            initializer { EventsModel(service) }
        }
    }
}
