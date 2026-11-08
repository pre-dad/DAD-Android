package org.dad.foundation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

// Events screens (PRD E-1..E-10): the list, an event's detail with reserve / waitlist / cancel, check-in by
// typed or scanned code, add-to-calendar and report-an-issue, bound to [EventsModel]. Every confirmation is
// inline (no OS dialog) so it is deterministic to test. Scanning is a live CameraX + ML Kit viewfinder (see
// [ScanQrSection]); the camera permission is asked only when the donor taps "Scan QR code", and typing the code
// always works.

/** The events section: the list, with the open event's detail on top of it. */
@Composable
fun EventsScreen(model: EventsModel, onClose: (() -> Unit)? = null, onSignedOut: () -> Unit = {}) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> model.events.firstOrNull { it.id == id } }
    // An event that vanished (cancelled away, or hidden by the invite-only gate) closes its own detail.
    LaunchedEffect(selected == null, model.phase) {
        if (selectedId != null && selected == null && model.phase == EventsPhase.Ready) selectedId = null
    }
    if (selected != null) {
        BackHandler { selectedId = null; model.resetCheckIn() }
        EventDetail(model, selected, onBack = { selectedId = null; model.resetCheckIn() })
    } else {
        if (onClose != null) BackHandler(onBack = onClose)
        EventList(model, onClose, onOpen = { id -> selectedId = id; model.resetCheckIn(); model.clearOutcome(); model.clearActionError(); model.open(id) })
    }
}

@Composable
private fun EventList(model: EventsModel, onClose: (() -> Unit)?, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start).widthIn(max = 640.dp).fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("events-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_events_volunteering), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            is EventsPhase.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("events-loading"))
            is EventsPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message.asString(), modifier = Modifier.testTag("events-error"))
                DadPrimaryButton(stringResource(R.string.common_try_again), Modifier.testTag("events-retry")) { model.load() }
            }
            is EventsPhase.Ready -> {
                model.actionError?.let { Text(it.asString(), modifier = Modifier.testTag("events-action-error").semantics { liveRegion = LiveRegionMode.Polite }) }
                if (model.events.isEmpty()) Text(stringResource(R.string.events_no_events_right_now_check_back), modifier = Modifier.testTag("events-empty"))
                model.events.forEach { EventRow(it, model.now(), onOpen) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventRow(event: DonorEvent, now: Date, onOpen: (String) -> Unit) {
    // Not clickable as a whole: a clickable row would merge the tagged texts away. The chevron button opens it.
    Row(modifier = Modifier.fillMaxWidth().testTag("event-${event.id}"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        val day = event.startsAt.toInstant().atZone(EventTime.zone(event.timeZone))
        Column(
            modifier = Modifier.clearAndSetSemantics {}.background(DadTheme.color("brand-wash"), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(day.format(DateTimeFormatter.ofPattern("MMM", Locale.US)).uppercase(Locale.US), style = MaterialTheme.typography.labelSmall, color = DadTheme.color("ink"))
            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, color = DadTheme.color("ink"))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            Text(EventTime.start(event), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("event-start-${event.id}"))
            Text(listOf(event.venue, event.city).filter { it.isNotBlank() }.joinToString(", ") + " · " + event.spotsLabel.asString(), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // State in text, not colour alone.
                if (event.isInviteOnly) Text(stringResource(R.string.events_invite_only), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("invite-only-${event.id}"))
                statusLabel(event, now)?.let { Text(it.asString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("event-status-${event.id}")) }
                if (event.earlyBooking && event.availability(now) is EventAvailability.Reservable && event.bookingOpensAt?.after(now) == true) {
                    Text(stringResource(R.string.events_early_booking), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("early-booking-${event.id}"))
                }
            }
        }
        IconButton(onClick = { onOpen(event.id) }, modifier = Modifier.size(48.dp).testTag("open-event-${event.id}")) {
            DadSymbol("chevron", stringResource(R.string.events_view_details_for, event.title))
        }
    }
}

private fun statusLabel(event: DonorEvent, now: Date): UiText? = when (event.availability(now)) {
    EventAvailability.CheckedIn -> UiText.res(R.string.events_checked_in)
    EventAvailability.Reserved -> UiText.res(R.string.events_youre_going)
    EventAvailability.Waitlisted -> UiText.res(R.string.events_on_the_waitlist)
    EventAvailability.Cancelled -> UiText.res(R.string.events_cancelled)
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDetail(model: EventsModel, event: DonorEvent, onBack: () -> Unit) {
    val now by produceState(model.now()) { while (true) { delay(30_000); value = model.now() } }
    val busy = event.id in model.busy
    var confirmingCancel by rememberSaveable(event.id) { mutableStateOf(false) }
    CalendarShare(model)
    val haptics = rememberDadHaptics()
    LaunchedEffect(model.lastOutcome) { if (model.lastOutcome != null) haptics.confirm() }
    Column(
        modifier = Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start).widthIn(max = 640.dp).fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("event-detail"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DadTextButton(stringResource(R.string.events_all_events), Modifier.testTag("event-back"), onClick = onBack)
        Text(event.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading().testTag("event-title"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(event.typeLabel.asString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("event-type"))
            if (event.isInviteOnly) Text(stringResource(R.string.events_invite_only), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("invite-only-${event.id}"))
        }
        event.invitationNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("invitation-note")) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.events_when), style = MaterialTheme.typography.labelLarge)
            Text(EventTime.range(event), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("event-time"))
            Text(stringResource(R.string.events_where), style = MaterialTheme.typography.labelLarge)
            Text(
                listOf(event.venue, event.address, event.city).filter { it.isNotBlank() }.joinToString("\n"),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("event-place"),
            )
            Text(event.spotsLabel.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("event-spots"))
        }
        if (event.details.isNotBlank()) Text(event.details, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("event-details"))

        model.actionError?.let { Text(it.asString(), modifier = Modifier.testTag("events-action-error").semantics { liveRegion = LiveRegionMode.Polite }) }
        model.lastOutcome?.let { outcome ->
            Column(modifier = Modifier.testTag("reserve-confirm").semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(outcome.confirmationTitle.asString(), style = MaterialTheme.typography.titleMedium)
                Text(outcome.confirmationDetail.asString(), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { model.clearOutcome() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.events_done)) }
            }
        }

        when (val availability = event.availability(now)) {
            is EventAvailability.Reservable ->
                DadPrimaryButton(if (busy) stringResource(R.string.events_reserving) else stringResource(R.string.events_reserve), Modifier.testTag("reserve-${event.id}")) { if (!busy) model.reserve(event.id) }
            EventAvailability.Waitlistable ->
                DadPrimaryButton(if (busy) stringResource(R.string.events_joining) else stringResource(R.string.events_join_waitlist), Modifier.testTag("waitlist-${event.id}")) { if (!busy) model.reserve(event.id) }
            is EventAvailability.NotYetOpen ->
                Text(stringResource(R.string.events_booking_opens, EventTime.at(event, availability.opensAt)), modifier = Modifier.testTag("not-yet-open-${event.id}"))
            EventAvailability.Started -> Text(stringResource(R.string.events_this_event_has_started_so_it_cant), modifier = Modifier.testTag("started-${event.id}"))
            EventAvailability.Cancelled -> Text(stringResource(R.string.events_this_event_was_cancelled), modifier = Modifier.testTag("cancelled-${event.id}"))
            EventAvailability.Reserved -> Text(stringResource(R.string.events_youre_going_your_spot_is_reserved), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("going-${event.id}"))
            EventAvailability.Waitlisted -> Text(stringResource(R.string.events_youre_on_the_waitlist), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("waitlisted-${event.id}"))
            EventAvailability.CheckedIn -> Text(
                event.checkedInAt?.let { stringResource(R.string.events_checked_in_at, EventTime.at(event, it)) } ?: stringResource(R.string.events_checked_in_plain),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("checkedin-${event.id}"),
            )
        }

        if (event.canCancel(now)) {
            if (confirmingCancel) {
                Column(modifier = Modifier.testTag("cancel-confirm-${event.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (event.status == ReservationStatus.WAITLISTED) stringResource(R.string.events_leave_the_waitlist) else stringResource(R.string.events_give_up_your_spot), style = MaterialTheme.typography.bodyLarge)
                    DadDestructiveTextButton(
                        if (event.status == ReservationStatus.WAITLISTED) stringResource(R.string.events_leave_the_waitlist_2) else stringResource(R.string.events_give_up_my_spot),
                        Modifier.fillMaxWidth().testTag("cancel-confirm-go"), enabled = !busy,
                    ) { confirmingCancel = false; model.cancel(event.id) }
                    DadSecondaryButton(stringResource(R.string.events_keep_it), Modifier.fillMaxWidth().testTag("cancel-keep")) { confirmingCancel = false }
                }
            } else {
                OutlinedButton(onClick = { confirmingCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel-${event.id}")) {
                    Text(if (event.status == ReservationStatus.WAITLISTED) stringResource(R.string.events_leave_waitlist) else stringResource(R.string.events_cancel_my_spot))
                }
            }
        }

        if (event.status == ReservationStatus.RESERVED && !event.cancelled) CheckInSection(model, event, now)

        if (event.status == ReservationStatus.RESERVED && !event.cancelled) AddToCalendar(model, event)

        event.reportIssueUrl?.let { url -> ReportIssue(url) }
    }
}

// MARK: check-in (E-8)

@Composable
private fun CheckInSection(model: EventsModel, event: DonorEvent, now: Date) {
    val state = model.checkIn
    Column(Modifier.fillMaxWidth().testTag("checkin-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.events_check_in), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (event.checkedInAt != null) {
            Text(stringResource(R.string.events_youre_already_checked_in), modifier = Modifier.testTag("checkin-already"))
            return@Column
        }
        if (!event.canCheckIn(now)) {
            val closed = if (now.before(event.startsAt)) stringResource(R.string.events_check_in_opens_when_the_event) else stringResource(R.string.events_check_in_for_this_event_has_closed)
            Text(closed, modifier = Modifier.testTag("checkin-closed"))
            return@Column
        }
        var code by rememberSaveable(event.id) { mutableStateOf("") }
        val checking = state is CheckInState.Checking
        Text(stringResource(R.string.events_enter_the_code_shown_at_the_event), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = code, onValueChange = { code = it.take(CheckInCode.LENGTH + 8); model.resetCheckIn() },
            label = { Text(stringResource(R.string.events_check_in_code)) }, singleLine = true, enabled = !checking,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().testTag("checkin-code"),
        )
        DadPrimaryButton(if (checking) stringResource(R.string.events_checking_in) else stringResource(R.string.events_check_in), Modifier.testTag("checkin-submit")) { if (!checking) model.checkIn(event, code) }
        ScanQrSection(enabled = !checking, onToken = { model.checkIn(event, it) }, onScanStarted = { model.resetCheckIn() })
        when (state) {
            is CheckInState.Done -> Text(
                if (state.alreadyCheckedIn) stringResource(R.string.events_you_were_already_checked_in) else stringResource(R.string.events_youre_checked_in_thank_you_for),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("checkin-result").semantics { liveRegion = LiveRegionMode.Polite },
            )
            is CheckInState.Failed -> Text(state.message.asString(), modifier = Modifier.testTag("checkin-error").semantics { liveRegion = LiveRegionMode.Polite })
            else -> Unit
        }
    }
}

// MARK: calendar (E-6)

@Composable
private fun AddToCalendar(model: EventsModel, event: DonorEvent) {
    val state = model.calendar
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { model.addToCalendar(event) }, enabled = state !is CalendarState.Fetching,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-calendar"),
        ) { Text(if (state is CalendarState.Fetching) stringResource(R.string.events_getting_calendar_file) else stringResource(R.string.events_add_to_calendar)) }
        if (state is CalendarState.Failed) Text(state.message.asString(), modifier = Modifier.testTag("calendar-error").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** Offers a fetched `.ics` through the share sheet via a FileProvider URI (no calendar permission), then clears it. */
@Composable
private fun CalendarShare(model: EventsModel) {
    val context = LocalContext.current
    val state = model.calendar
    val chooserTitle = stringResource(R.string.events_add_to_calendar)
    LaunchedEffect(state) {
        if (state !is CalendarState.Ready) return@LaunchedEffect
        val file = withContext(Dispatchers.IO) { sharedFile(context, "calendar", "event.ics").also { it.writeBytes(state.bytes) } }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, fileAuthority(context), file))
            putExtra(Intent.EXTRA_SUBJECT, state.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            model.calendarShared()
        } catch (_: ActivityNotFoundException) {
            model.calendarUnavailable()
        }
    }
}

// MARK: report an issue (E-10)

@Composable
private fun ReportIssue(url: String) {
    val context = LocalContext.current
    val mailto = remember(url) { url.toUri().takeIf { it.scheme.equals("mailto", ignoreCase = true) } } ?: return
    var failed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, mailto))
                } catch (_: ActivityNotFoundException) {
                    failed = true
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report-issue"),
        ) { Text(stringResource(R.string.events_report_an_issue)) }
        if (failed) Text(stringResource(R.string.events_no_email_app, mailto.schemeSpecificPart.substringBefore('?')), modifier = Modifier.testTag("report-issue-error"))
    }
}

internal fun fileAuthority(context: Context) = "${context.packageName}.files"

internal fun sharedFile(context: Context, folder: String, name: String): File =
    File(File(context.cacheDir, "shared/$folder").apply { mkdirs() }, name)
