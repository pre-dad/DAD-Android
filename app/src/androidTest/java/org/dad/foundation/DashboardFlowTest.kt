package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Runs the REAL [WireDashboard], [AccountSession] and [WireAPI] against the DEBUG-only scripted transport
 * (`stub_backend`) and starts with a stored session (`stub_signed_in`), so the donor home renders stubbed
 * server data through the release code path with no network. Scenario extras: `stub_dashboard_empty`,
 * `stub_dashboard_fail_first`, `stub_dashboard_unranked`.
 */
class DashboardFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        return compose.launchReady(intent)
    }

    /** Scroll the history list until [tag] is composed, retrying while a page is still loading. */
    private fun scrollHistoryTo(tag: String) {
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("history-screen").performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
    }

    private fun openHistory() {
        compose.waitForTag("open-history")
        // The button is composed immediately, but both loading indicators above it are replaced by taller
        // cards when the data lands, moving it. Click only once the layout has settled.
        compose.waitUntil(15_000) { !compose.tagExists("dashboard-loading") && !compose.tagExists("initiative-loading") }
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("open-history").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithTag("open-history").performScrollTo().performClick()
        compose.waitForTag("history-screen")
    }

    @Test fun restoringASessionLoadsTheDashboardExactlyOnce() {
        DebugBackend.resetRequestLog()
        launch().use {
            compose.waitForTag("streak-count")
            compose.waitForTag("initiative-title")
            compose.waitForIdle()
            val log = DebugBackend.requestLog.toList()
            listOf("GET /donor/me/profile", "GET /donor/me/dashboard", "GET /donor/me/today-initiative").forEach { call ->
                assertEquals("$call must run once after restore: $log", 1, log.count { it == call })
            }
        }
    }

    @Test fun signedInDashboardRendersStubbedServerData() {
        launch().use {
            compose.waitForTag("streak-count")
            compose.onNodeWithTag("streak-count").assertTextEquals("18-day streak")
            compose.onNodeWithTag("streak-next").assertTextEquals("12 days to your 30-day milestone")
            // national_percentile 2 is the top bucket: "Top 2%", never "Top 98%".
            compose.onNodeWithTag("rank-national").assertTextEquals("Top 2% nationally")
            compose.onNodeWithTag("rank-city").assertTextEquals("#4 in Austin")
            compose.onNodeWithTag("dash-given").assertTextEquals("$1,240")
            compose.onNodeWithTag("network-annualized").assertTextContains("$4,745", substring = true)
            compose.onNodeWithTag("founding-donor").assertTextContains("Founding donor", substring = true)
            compose.waitForTag("initiative-title")
            compose.onNodeWithTag("initiative-title").assertTextEquals("Meals on Wheels")
            compose.onNodeWithTag("initiative-note").assertTextContains("not designated giving", substring = true)
        }
    }

    @Test fun giftHistoryPagesToTheEndAndShowsAGiftRecord() {
        launch().use {
            openHistory()
            compose.waitForTag("gift-row-0")
            compose.onNodeWithTag("history-year-total").assertTextEquals("$3,515")
            scrollHistoryTo("history-more")
            compose.onNodeWithTag("history-more").performClick()
            scrollHistoryTo("gift-row-99")
            scrollHistoryTo("history-more")
            compose.onNodeWithTag("history-more").performClick()
            scrollHistoryTo("gift-row-119")
            assertTrue("every page was loaded", !compose.tagExists("gift-row-120"))
        }
    }

    @Test fun theLastPageEndsPaginationAndAGiftOpensItsRecord() {
        launch().use {
            openHistory()
            compose.waitForTag("gift-row-0")
            repeat(2) {
                scrollHistoryTo("history-more")
                compose.onNodeWithTag("history-more").performClick()
                compose.waitForIdle()
            }
            scrollHistoryTo("gift-row-119")
            assertTrue("the last page ends pagination", !compose.tagExists("history-more"))
            scrollHistoryTo("gift-row-0")
            compose.onNodeWithTag("gift-row-0").performClick()
            compose.waitForTag("receipt-detail")
            compose.onNodeWithTag("receipt-detail").assertIsDisplayed()
            // history-close lives in the always-visible TopAppBar (not the scrollable list), so tap it directly.
            compose.onNodeWithTag("history-close").performClick()
            compose.waitForTag("open-history")
        }
    }

    @Test fun emptyStatesAreExplained() {
        launch("stub_dashboard_empty").use {
            compose.waitForTag("dashboard-preparing")
            compose.waitForTag("initiative-none")
            assertTrue(!compose.tagExists("streak-count"))
            openHistory()
            compose.waitForTag("history-empty")
        }
    }

    @Test fun errorsOfferRetryAndRecover() {
        launch("stub_dashboard_fail_first").use {
            compose.waitForTag("dashboard-error")
            compose.onNodeWithTag("dashboard-retry").performScrollTo().performClick()
            compose.waitForTag("streak-count")
            openHistory()
            compose.waitForTag("history-error")
            compose.onNodeWithTag("history-retry").performClick()
            compose.waitForTag("gift-row-0")
        }
    }

    @Test fun optedOutAndUnknownFiguresAreNeverShownAsZero() {
        launch("stub_dashboard_unranked").use {
            compose.waitForTag("streak-unknown")
            assertTrue(!compose.tagExists("rank-national"))
            assertTrue("an opted-out donor sees no rank line", !compose.tagExists("rank-unranked"))
            compose.onNodeWithTag("dash-given").assertTextEquals("--")
            assertTrue(!compose.tagExists("network-card"))
            assertTrue(!compose.tagExists("founding-donor"))
        }
    }

    @Test fun insightsDrawKnownBlocksSkipUnknownOnesAndLinkIntoTheApp() {
        launch("stub_dashboard_insights").use {
            compose.waitForTag("insights")
            compose.onNodeWithTag("insights-title").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("insights-0-heading").assertTextEquals("Your impact")
            assertTrue("an unknown node type draws nothing, so the link is the third block", !compose.tagExists("insights-3-link"))
            compose.onNodeWithTag("insights-1-metric-0").assertTextContains("$1,240")
            compose.onNodeWithTag("insights-1-metric-1").assertTextContains("18 days")
            compose.onNodeWithTag("insights-2-link").performScrollTo().performClick()
            compose.waitUntil(15_000) { !compose.tagExists("insights") }
        }
    }

    @Test fun noInsightsCardWithoutAPanel() {
        launch().use {
            compose.waitForTag("streak-count")
            assertTrue(!compose.tagExists("insights"))
        }
    }
}
