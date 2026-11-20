package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Events screens against the in-memory preview service (`preview_events` extra), so they render without a
 * session. Every surface is inline (no dialogs). The wired journey over the real conformer is [EventsWiredFlowTest].
 */
class EventsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("preview_events", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return compose.launchReady(intent)
    }

    @Test fun eventsHomeRendersListAndInviteOnlyMarker() {
        launch().use {
            compose.waitForTag("events-home")
            compose.onNodeWithTag("events-home").assertIsDisplayed()
            compose.waitForTag("open-event-e1")
            compose.onNodeWithTag("event-start-e1").performScrollTo().assertIsDisplayed()
            // The invite-only event carries a visible marker (E-5).
            compose.onNodeWithTag("invite-only-e3").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun reserveShowsInlineConfirmationOnTheDetail() {
        launch().use {
            compose.waitForTag("open-event-e1")
            compose.onNodeWithTag("open-event-e1").performClick()
            compose.waitForTag("reserve-e1")
            compose.onNodeWithTag("event-time").assertIsDisplayed()
            compose.onNodeWithTag("reserve-e1").performClick()
            compose.waitForTag("reserve-confirm")
            compose.onNodeWithTag("reserve-confirm").assertIsDisplayed()
        }
    }

    @Test fun aFullEventOffersTheWaitlistAndCancelUsesInlineConfirmation() {
        launch().use {
            compose.waitForTag("open-event-e2")
            compose.onNodeWithTag("open-event-e2").performClick()
            compose.waitForTag("waitlist-e2")
            compose.onNodeWithTag("waitlist-e2").performClick()
            compose.waitForTag("waitlisted-e2")
            compose.onNodeWithTag("cancel-e2").performScrollTo().performClick()
            compose.waitForTag("cancel-confirm-e2")
            compose.onNodeWithText("Keep it").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Leave the waitlist").performScrollTo().performClick()
            // The place is released, so the event has a spot again and offers Reserve.
            compose.waitForTag("reserve-e2")
        }
    }
}
