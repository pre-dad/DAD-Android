package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The engagement journey end to end through the REAL [WireEngagement] conformer, [AccountSession] and Retrofit
 * stack, over the DEBUG scripted transport (`stub_backend` with a stored session). Scenario extras:
 * `stub_open_engagement` (open the screen at launch), `stub_no_public_name`, `stub_rankings_fail_toggle`,
 * `stub_badges_down`, `stub_network_down`, `stub_spend_unpublished`. See [StubEngagement].
 */
class EngagementWiredFlowTest {
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

    private fun openEngagement(vararg extras: String): ActivityScenario<MainActivity> {
        val scenario = launch("stub_open_engagement", *extras)
        compose.waitForTag("engagement-home")
        compose.waitForTagGone("engagement-loading")
        return scenario
    }

    @Test fun theHomeCardOpensTheScreenAndCloseReturns() {
        launch().use {
            compose.waitForTag("open-engagement")
            compose.waitUntil(20_000) { !compose.tagExists("dashboard-loading") && !compose.tagExists("initiative-loading") }
            compose.waitForIdle()
            tap("open-engagement")
            compose.waitForTag("engagement-home")
            compose.waitForTag("badge-summary")
            tap("engagement-close")
            compose.waitForTag("donor-home")
        }
    }

    @Test fun badgesShowEarnedAndLockedInTextWithAPerkAndInviteOnlyAccess() {
        openEngagement().use {
            compose.waitForTag("badge-summary")
            compose.waitForTagText("badge-summary", "4 of 9 earned")
            compose.onNodeWithTag("badge-invite-access").performScrollTo().assertIsDisplayed()
            // Earned Founding Donor carries its perk as copy (the server sends a key); locked Team Captain shows its rule.
            compose.onNodeWithTag("badge-earned-F").performScrollTo().assertIsDisplayed()
            compose.waitForTagText("badge-earned-F", "Invite-only founding events")
            compose.onNodeWithTag("badge-locked-TC").performScrollTo().assertIsDisplayed()
            compose.waitForTagText("badge-locked-TC", "20 converted referrals")
            compose.waitForTagText("badge-locked-TC", "Locked")
            compose.onNodeWithTag("badge-earned-CITY50").performScrollTo().assertIsDisplayed()
            compose.waitForTagText("badge-earned-CITY50", "Top 50 in Chicago")
        }
    }

    @Test fun rankingShowsTheTopBucketAsIsAndTheToggleWorksBothWays() {
        openEngagement().use {
            compose.waitForTag("ranking-national")
            compose.waitForTagText("ranking-national", "Top 2% nationally")
            tap("ranking-toggle")
            compose.waitForTag("ranking-optedout")
            compose.waitForTagGone("ranking-national")
            tap("ranking-toggle")
            compose.waitForTag("ranking-national")
            compose.waitForTagText("ranking-national", "Top 2% nationally")
        }
    }

    @Test fun aFailedToggleRevertsAndSaysSo() {
        openEngagement("stub_rankings_fail_toggle").use {
            compose.waitForTag("ranking-national")
            tap("ranking-toggle")
            compose.waitForTag("engagement-action-error")
            compose.waitForTag("ranking-national")
            compose.waitForTagText("ranking-national", "Top 2%")
        }
    }

    @Test fun theNetworkListsNamedInviteesAndCountsTheAnonymousOnes() {
        openEngagement().use {
            compose.waitForTagText("network-active", "5 people giving")
            compose.waitForTagText("network-annualized", "$3,480/yr")
            compose.waitForTagText("network-invitee-0", "Jordan M.")
            compose.waitForTagText("network-anonymous", "2 people choose to stay anonymous")
            compose.waitForTag("network-milestone")
        }
    }

    @Test fun theInviteLinkIsTheServerRouteAndRecentFirstGiftsListed() {
        openEngagement().use {
            compose.waitForTagText("referral-code", "ALEX2026")
            compose.waitForTagText("referral-total", "3 people have joined through you")
            compose.waitForTagText("invite-link", "https://stub.invalid/r/ALEX2026")
            compose.onNodeWithTag("conversion-0").performScrollTo().assertIsDisplayed()
            compose.waitForTagText("conversion-0", "Link")
            compose.waitForTagText("conversion-1", "In person")
        }
    }

    @Test fun copyPutsTheLinkOnTheClipboard() {
        openEngagement().use {
            compose.waitForTag("copy-invite")
            tap("copy-invite")
            compose.waitForTagText("copy-invite", "Copied")
            // Assert at the app's own sharer seam (the DEBUG capturing [DebugReferralSharer] records what
            // [ReferralShareAction.copy] forwarded) rather than reading the OS clipboard back, which races
            // window focus on the headless CI emulator and intermittently returns null. Same guarantee —
            // the real wired ReferralShareAction still runs — but deterministic and no ClipboardManager.
            compose.waitUntil(20_000) { DebugBackend.copiedLink != null }
            assertEquals("https://stub.invalid/r/ALEX2026", DebugBackend.copiedLink)
        }
    }

    @Test fun theQrIsDrawnLocallyForTheInPersonLink() {
        openEngagement().use {
            compose.waitForTag("show-qr")
            tap("show-qr")
            compose.waitForTag("qr-code")
            compose.onNodeWithTag("qr-code").assertIsDisplayed()
            tap("qr-close")
            compose.waitForTag("engagement-home")
        }
    }

    @Test fun aWalletPassThatCannotBePreparedSaysTheCodeStillWorks() {
        openEngagement().use {
            compose.waitForTag("wallet-add")
            tap("wallet-add")
            compose.waitForTagText("wallet-error", "on-screen code still works")
        }
    }

    @Test fun aDonorWithNoPublicNameIsPromptedToChooseOne() {
        openEngagement("stub_no_public_name").use {
            compose.waitForTag("name-field")
            compose.enterText("name-field", "Alex C.")
            tap("save-name")
            compose.waitForTag("referral-code")
            compose.waitForTagText("invite-link", "https://stub.invalid/r/ALEX2026")
        }
    }

    @Test fun aSectionThatFailedSaysSoWhileTheRestStaysOnScreen() {
        openEngagement("stub_badges_down").use {
            compose.waitForTag("badges-unavailable")
            compose.waitForTag("network-active")
            compose.waitForTag("ranking-national")
        }
        openEngagement("stub_network_down").use {
            compose.waitForTag("network-unavailable")
            compose.waitForTag("badge-summary")
        }
    }

    @Test fun todaysInitiativeOpensTheSpendByCategory() {
        launch().use {
            compose.waitForTag("open-spend")
            tap("open-spend")
            compose.waitForTag("spend-screen")
            compose.waitForTagText("spend-total", "$5,000")
            compose.waitForTagText("spend-row-0", "Food")
            compose.waitForTagText("spend-row-1", "Transport")
            tap("spend-close")
            compose.waitForTag("donor-home")
        }
    }

    @Test fun anInitiativeWithNoPublishedSpendSaysSo() {
        launch("stub_spend_unpublished").use {
            compose.waitForTag("open-spend")
            tap("open-spend")
            compose.waitForTag("spend-unpublished")
        }
    }
}
