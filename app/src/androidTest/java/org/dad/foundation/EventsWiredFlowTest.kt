package org.dad.foundation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * The events journey end to end through the REAL [WireEvents] conformer, [AccountSession] and Retrofit stack,
 * over the DEBUG scripted transport (`stub_backend` with a stored session). Scenario extras:
 * `stub_events_invited` (the invite-only event is returned), `stub_events_fail_first` (the first list read is a
 * 503), `stub_events_not_eligible` (every reserve is a 403). See [StubEvents] for the scripted events.
 */
class EventsWiredFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        DebugBackend.resetRequestLog()
        return compose.launchReady(intent)
    }

    private fun tap(tag: String) = compose.tapSafely(tag)

    private fun textMatches(tag: String, pattern: Regex) = compose.waitUntil(20_000) {
        runCatching {
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }.contains(pattern)
        }.getOrDefault(false)
    }

    private fun openEvents() {
        compose.waitForTag("open-events")
        compose.waitUntil(20_000) { !compose.tagExists("dashboard-loading") && !compose.tagExists("initiative-loading") }
        compose.waitForIdle()
        tap("open-events")
        compose.waitForTag("events-home")
    }

    private fun openEvent(id: String) {
        compose.waitForTag("open-event-$id")
        tap("open-event-$id")
        compose.waitForTag("event-detail")
        compose.waitForTag("event-title")
    }

    private fun backToList() {
        tap("event-back")
        compose.waitForTag("events-home")
    }

    @Test fun browseListThenDetailThenReserveThenCancel() {
        launch().use {
            openEvents()
            compose.waitForTag("open-event-${StubEvents.PARK}")
            // The time shows in the venue's zone, with the zone named (E-3).
            compose.waitUntil(20_000) {
                runCatching { compose.onNodeWithTag("event-start-${StubEvents.PARK}").assertTextContains("CST", substring = true) }.isSuccess ||
                    runCatching { compose.onNodeWithTag("event-start-${StubEvents.PARK}").assertTextContains("CDT", substring = true) }.isSuccess
            }
            openEvent(StubEvents.PARK)
            textMatches("event-time", Regex("\\bC[SD]T\\b"))
            compose.waitForTagText("event-spots", "5 spots left")
            tap("reserve-${StubEvents.PARK}")
            compose.waitForTag("reserve-confirm")
            compose.waitForTag("going-${StubEvents.PARK}")
            compose.waitForTagText("event-spots", "4 spots left")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/events/${StubEvents.PARK}/reserve"))
            // Cancel keeps a prior state until confirmed, then releases the seat.
            tap("cancel-${StubEvents.PARK}")
            compose.waitForTag("cancel-confirm-${StubEvents.PARK}")
            tap("cancel-keep")
            compose.waitForTagGone("cancel-confirm-${StubEvents.PARK}")
            compose.waitForTag("going-${StubEvents.PARK}")
            tap("cancel-${StubEvents.PARK}")
            tap("cancel-confirm-go")
            compose.waitForTag("reserve-${StubEvents.PARK}")
            compose.waitForTagText("event-spots", "5 spots left")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/events/${StubEvents.PARK}/cancel"))
        }
    }

    @Test fun aFullEventJoinsTheWaitlist() {
        launch().use {
            openEvents()
            openEvent(StubEvents.FOOD)
            compose.waitForTagText("event-spots", "Full")
            tap("waitlist-${StubEvents.FOOD}")
            compose.waitForTag("waitlisted-${StubEvents.FOOD}")
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("on the waitlist", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            backToList()
            compose.waitForTagText("event-status-${StubEvents.FOOD}", "On the waitlist")
        }
    }

    @Test fun theInviteOnlyEventIsHiddenUnlessTheServerReturnsIt() {
        launch().use {
            openEvents()
            compose.waitForTag("open-event-${StubEvents.PARK}")
            assertTrue(!compose.tagExists("open-event-${StubEvents.DINNER}"))
        }
    }

    @Test fun theInviteOnlyEventAppearsWhenTheServerReturnsIt() {
        launch("stub_events_invited").use {
            openEvents()
            compose.waitForTag("open-event-${StubEvents.DINNER}")
            compose.waitForTag("invite-only-${StubEvents.DINNER}")
            openEvent(StubEvents.DINNER)
            compose.waitForTagText("invitation-note", "Invitation earned")
        }
    }

    @Test fun checkInInsideTheWindowWithTheRightCode() {
        launch().use {
            openEvents()
            openEvent(StubEvents.LIVE)
            compose.waitForTag("checkin-code")
            // A wrong (well formed) code is refused with the reason, and nothing is checked in.
            compose.enterText("checkin-code", StubEvents.WRONG_TOKEN)
            tap("checkin-submit")
            compose.waitForTag("checkin-error")
            compose.waitForTagText("checkin-error", "didn’t work")
            // A malformed code never reaches the server.
            val before = DebugBackend.requestLog.count { it == "POST /donor/me/events/check-in" }
            compose.enterText("checkin-code", "short", replace = true)
            tap("checkin-submit")
            compose.waitForTagText("checkin-error", "66 characters")
            assertEquals(before, DebugBackend.requestLog.count { it == "POST /donor/me/events/check-in" })
            // The real code checks in.
            compose.enterText("checkin-code", StubEvents.TOKEN, replace = true)
            tap("checkin-submit")
            compose.waitForTag("checkedin-${StubEvents.LIVE}")
            compose.waitForTagGone("checkin-code")
            // Checked-in survives a reload of the list (checked_in_at comes back from the server).
            backToList()
            tap("events-back")
            compose.waitForTag("open-events")
            tap("open-events")
            compose.waitForTagText("event-status-${StubEvents.LIVE}", "Checked in")
        }
    }

    @Test fun checkInOutsideTheWindowIsNotOffered() {
        launch().use {
            openEvents()
            openEvent(StubEvents.LATER)
            compose.waitForTag("checkin-closed")
            compose.waitForTagText("checkin-closed", "opens when the event starts")
            assertTrue(!compose.tagExists("checkin-code"))
            // New York venue: the zone is the venue's.
            textMatches("event-time", Regex("\\bE[SD]T\\b"))
        }
    }

    @Test fun addToCalendarHandsTheShareChooserTheIcsFile() {
        // The system share sheet is another app and outlives the test on the headless emulator, so the chooser is
        // intercepted at the app's own startActivity and inspected instead of being opened.
        val chooser = AtomicReference<Intent?>(null)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            launch().use {
                openEvents()
                openEvent(StubEvents.LATER)
                tap("add-calendar")
                compose.waitUntil(20_000) { DebugBackend.requestLog.contains("GET /donor/me/events/${StubEvents.LATER}/calendar.ics") }
                compose.waitUntil(20_000) { chooser.get() != null }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        @Suppress("DEPRECATION")
        val send = checkNotNull(chooser.get()).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_SEND, send?.action)
        assertEquals("text/calendar", send?.type)
        @Suppress("DEPRECATION")
        val stream = send?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertTrue("$stream", stream?.path?.endsWith(".ics") == true)
    }

    @Test fun aDonorWhoIsNotEligibleIsToldSo() {
        launch("stub_events_not_eligible").use {
            openEvents()
            openEvent(StubEvents.PARK)
            tap("reserve-${StubEvents.PARK}")
            compose.waitForTag("events-action-error")
            compose.waitForTagText("events-action-error", "not eligible")
            compose.waitForTag("reserve-${StubEvents.PARK}")
        }
    }

    @Test fun aFailedLoadOffersARetry() {
        launch("stub_events_fail_first").use {
            openEvents()
            compose.waitForTag("events-error")
            tap("events-retry")
            compose.waitForTag("open-event-${StubEvents.PARK}")
            compose.waitForTagGone("events-error")
        }
    }
}
