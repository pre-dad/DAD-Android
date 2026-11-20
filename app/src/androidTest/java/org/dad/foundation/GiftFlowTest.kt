package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The giving journey end to end through the REAL [WireGiving] conformer, [AccountSession] and Retrofit stack,
 * over the DEBUG scripted transport (`stub_backend`, with a stored session via `stub_signed_in`). The hosted
 * Stripe pages are stubbed by an in-app stand-in whose "Complete payment" plays the Stripe webhook. Scenario
 * extras: `stub_gift_active` (a $150/month gift), `stub_gift_fail_first` (the first recurring read is a 503).
 */
class GiftFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String, link: String? = null): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        link?.let { intent.data = android.net.Uri.parse(it) }
        DebugBackend.resetRequestLog()
        DebugBackend.checkoutReferral = null
        return compose.launchReady(intent)
    }

    private fun tap(tag: String) = compose.tapSafely(tag)

    /** Wait for the home gift card to finish loading, then open the gift screen from it. */
    private fun openGift() {
        compose.waitForTag("open-gift")
        compose.waitUntil(20_000) { !compose.tagExists("dashboard-loading") && !compose.tagExists("initiative-loading") }
        compose.waitForIdle()
        tap("open-gift")
        compose.waitForTag("gift-screen")
    }

    private fun waitSummary(text: String) = compose.waitUntil(20_000) {
        runCatching { compose.onNodeWithTag("gift-summary").assertTextContains(text, substring = true) }.isSuccess
    }

    private fun closeGift() {
        tap("gift-close")
        compose.waitForTag("donor-home")
    }

    @Test fun theHomeGiftAmountMatchesTheLoadedSubscription() {
        launch("stub_gift_active").use {
            waitSummary("$150/month")
            compose.onNodeWithTag("open-gift").assertTextContains("$150/month", substring = true)
            compose.onNodeWithTag("open-gift").assertTextContains("$5 a day", substring = true)
        }
    }

    @Test fun aGiftStartedAfterAnInviteLinkCreditsTheReferrer() {
        launch(link = "https://stub.invalid/r/ALEX42?via=irl").use {
            compose.waitUntil(20_000) { DebugBackend.requestLog.contains("POST /donor/referrals/ALEX42") }
            compose.waitForTag("gift-summary")
            waitSummary("No monthly gift yet.")
            openGift()
            compose.waitForTag("start-gift")
            tap("start-gift")
            compose.waitForTag("hosted-page-stub")
            assertEquals(DebugBackend.REFERRAL_TOKEN, DebugBackend.checkoutReferral)
        }
    }

    @Test fun startAGiftThroughTheHostedPageThenTheHomeCardShowsIt() {
        launch().use {
            compose.waitForTag("gift-summary")
            waitSummary("No monthly gift yet.")
            openGift()
            compose.waitForTag("start-gift")
            compose.waitForTag("gift-none")
            // Pick $6 a day, then start: the app sends the amount and opens the (stubbed) hosted page.
            tap("gift-chip-other")
            repeat(5) { tap("gift-increase") }
            compose.onNodeWithTag("gift-daily").assertTextEquals("$6 a day")
            tap("start-gift")
            compose.waitForTag("hosted-page-stub")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/recurring/checkout"))
            tap("stub-complete")
            // The first-gift celebration covers the screen; the stub's founding_donor is true.
            compose.waitForTag("celebration-screen")
            compose.waitForTag("celebration-founding")
            tap("celebration-done")
            compose.waitForTag("gift-monthly")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$180/month")
            compose.onNodeWithTag("gift-notice").assertTextEquals("Your gift is set up.")
            closeGift()
            waitSummary("$180/month · renews")
            compose.onNodeWithTag("open-gift").assertTextContains("$180/month", substring = true)
            compose.onNodeWithTag("open-gift").assertTextContains("$6 a day", substring = true)
        }
    }

    @Test fun closingTheHostedPageWithoutPayingLeavesNoGift() {
        launch().use {
            openGift()
            tap("start-gift")
            compose.waitForTag("hosted-page-stub")
            tap("stub-close")
            compose.waitForTag("gift-notice")
            compose.onNodeWithTag("gift-notice").assertTextContains("We haven’t seen a new gift yet", substring = true)
            assertTrue(compose.tagExists("gift-none"))
            assertEquals(1, DebugBackend.requestLog.count { it == "POST /donor/me/recurring/checkout" })
            closeGift()
            waitSummary("No monthly gift yet.")
        }
    }

    @Test fun changeTheAmount() {
        launch("stub_gift_active").use {
            openGift()
            compose.waitForTag("gift-monthly")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$150/month")
            compose.onNodeWithTag("gift-daily").assertTextEquals("$5 a day")
            tap("gift-chip-other")
            tap("gift-increase")
            tap("save-gift")
            compose.waitForTag("gift-notice")
            compose.onNodeWithTag("gift-notice").assertTextEquals("Saved. Your next renewal will be $180.")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$180/month")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/recurring/amount"))
            closeGift()
            waitSummary("$180/month")
        }
    }

    @Test fun cancelTheMonthlyGiftThenTheHomeCardShowsItEnding() {
        launch("stub_gift_active").use {
            openGift()
            tap("gift-cancel")
            compose.waitForTag("cancel-confirm")
            compose.onNodeWithTag("cancel-confirm").assertIsDisplayed()
            tap("cancel-end")
            compose.waitForTag("gift-ending")
            compose.onNodeWithTag("gift-ending").assertTextContains("won’t renew", substring = true)
            closeGift()
            waitSummary("no further renewals")
        }
    }

    @Test fun keepGivingLeavesTheGiftActive() {
        launch("stub_gift_active").use {
            openGift()
            tap("gift-cancel")
            compose.waitForTag("cancel-confirm")
            tap("cancel-keep")
            compose.waitForTagGone("cancel-confirm")
            assertTrue(compose.tagExists("gift-cancel"))
            assertTrue(!compose.tagExists("gift-ending"))
            assertTrue(DebugBackend.requestLog.none { it == "POST /donor/me/recurring/cancel" })
        }
    }

    @Test fun updateThePaymentMethodThroughTheHostedPage() {
        launch("stub_gift_active").use {
            openGift()
            compose.waitForTag("payment-status")
            tap("update-payment")
            compose.waitForTag("hosted-page-stub")
            tap("stub-complete")
            compose.waitForTag("gift-notice")
            compose.onNodeWithTag("gift-notice").assertTextEquals("Your payment method was updated.")
            compose.onNodeWithTag("payment-status").assertTextContains("Card updated", substring = true)
        }
    }

    @Test fun aFailedGiftReadShowsAMessageAndOpeningTheScreenRecovers() {
        launch("stub_gift_fail_first").use {
            compose.waitForTag("gift-summary")
            waitSummary("Your gift isn’t available right now.")
            openGift()
            compose.waitForTag("gift-monthly")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$150/month")
            closeGift()
            waitSummary("$150/month")
        }
    }

    private fun startDeleting() {
        compose.waitForTag("open-settings")
        compose.waitUntil(20_000) { !compose.tagExists("dashboard-loading") && !compose.tagExists("initiative-loading") }
        tap("open-settings")
        tap("delete-start")
        compose.waitForTag("delete-input")
        compose.enterText("delete-input", "DELETE")
    }

    @Test fun deleteAccountWithAnActiveGiftOffersTheCancelFlowThenDeletes() {
        launch("stub_gift_active").use {
            startDeleting()
            tap("delete-confirm")
            // The server refuses (409): the screen explains and offers the real cancel flow.
            compose.waitForTag("settings-action-error")
            compose.onNodeWithTag("settings-action-error").assertTextContains("Cancel your monthly gift first", substring = true)
            tap("delete-cancel-gift")
            compose.waitForTag("gift-screen")
            tap("gift-cancel")
            compose.waitForTag("cancel-confirm")
            tap("cancel-end")
            compose.waitForTag("gift-ending")
            tap("gift-close")
            // Back in Settings with the half-finished confirmation intact: with the gift no longer renewing, deleting goes through.
            compose.waitForTag("delete-confirm")
            tap("delete-confirm")
            compose.waitForTag("role-donor")
            assertEquals(1, DebugBackend.requestLog.count { it == "POST /donor/me/recurring/cancel" })
        }
    }

    @Test fun deleteWithNoGiftStillJustDeletes() {
        launch().use {
            startDeleting()
            tap("delete-confirm")
            compose.waitForTag("role-donor")
            assertTrue(!compose.tagExists("delete-cancel-gift"))
        }
    }

    @Test fun theLoadingLayoutReservesTheSpaceTheCardsFillSoGiftHistoryDoesNotMove() {
        launch("stub_dashboard_slow", "stub_gift_active").use {
            compose.waitForTag("dashboard-loading")
            compose.waitForTag("open-history")
            compose.waitForIdle()
            val density = compose.density.density
            val before = compose.onNodeWithTag("open-history").fetchSemanticsNode().boundsInRoot.top
            assertTrue("the measurement must be taken while the cards are still loading", compose.tagExists("dashboard-loading") && compose.tagExists("initiative-loading"))
            compose.waitUntil(30_000) { compose.tagExists("streak-count") && compose.tagExists("initiative-title") }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("open-history").fetchSemanticsNode().boundsInRoot.top
            val shiftDp = Math.abs(after - before) / density
            assertTrue("Gift history moved ${shiftDp}dp when the cards loaded (before=$before after=$after)", shiftDp <= SHIFT_TOLERANCE_DP)
        }
    }

    private companion object {
        /** The skeleton lays out the same cards in the same type styles, so the button lands where it will end up (measured under 1dp on the stub data; the old spinner moved it by hundreds). */
        const val SHIFT_TOLERANCE_DP = 4f
    }
}
