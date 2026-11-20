package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Runs the REAL sign-in, session and account-deletion conformers (WireAuth, AccountSession,
 * WireSettings) against the DEBUG-only scripted transport (`stub_backend`), so the release code
 * path is exercised on the emulator with no network. Codes: 123456 signs in, 000000 is throttled,
 * anything else is rejected.
 */
class StubBackendFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String, link: String? = null): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("stub_backend", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        link?.let { intent.data = android.net.Uri.parse(it) }
        return compose.launchReady(intent)
    }

    /** Waits until the tagged control exists AND is enabled, so a click never lands on a still-disabled button. */
    private fun waitEnabled(tag: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag).and(isEnabled())).fetchSemanticsNodes().isNotEmpty() }

    private fun signIn(code: String) {
        compose.waitForTag("role-donor")
        compose.tapSafely("role-donor")
        compose.waitForTag("method-phone")
        compose.tapSafely("method-phone")
        compose.waitForTag("phone-input")
        // The phone field is prefilled with the "+1 " country code, so REPLACE the field rather than
        // append to it (an append would produce "+1 +1415…", which fails the E.164 check).
        compose.enterText("phone-input", "+14155551234", replace = true)
        compose.waitForIdle()
        waitEnabled("send-code")
        compose.tapSafely("send-code")
        compose.waitForTag("code-input")
        // A complete 6-digit code submits by itself (and hides the keyboard / leaves the screen), so there is
        // no keyboard to settle and no Verify button left to tap.
        compose.enterText("code-input", code, settleAfter = false)
        compose.waitForIdle()
    }

    private fun deleteAccount() {
        compose.waitForTag("open-settings")
        compose.tapSafely("open-settings")
        compose.waitForTag("delete-start")
        compose.tapSafely("delete-start")
        compose.waitForTag("delete-input")
        compose.enterText("delete-input", "DELETE")
        compose.waitForIdle()
        compose.tapSafely("delete-confirm")
    }

    @Test fun anInviteLinkNamesTheReferrerBeforeSignIn() {
        launch(link = "https://stub.invalid/r/ALEX42").use {
            compose.waitForTag("auth-invitation", 20_000)
            compose.onNodeWithTag("auth-invitation").assertTextEquals("Alex R invited you to give with DAD.")
        }
    }

    @Test fun signInThenDeleteAccountReturnsToSignIn() {
        launch().use {
            signIn("123456")
            deleteAccount()
            compose.waitForTag("role-donor")
            compose.onNodeWithTag("role-donor").assertIsDisplayed()
        }
    }

    @Test fun wrongCodeKeepsPersonSignedOutOnCodeEntry() {
        launch().use {
            signIn("654321")
            compose.waitForTag("sign-in-error")
            assertTrue(compose.onAllNodes(hasTestTag("open-settings")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("code-input").assertIsDisplayed()
        }
    }

    @Test fun throttledCodeShowsTheWaitMessageAndStaysSignedOut() {
        launch().use {
            signIn("000000")
            compose.waitForTag("sign-in-error")
            assertTrue(compose.onAllNodes(hasTestTag("open-settings")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun blockedDeletionExplainsAndKeepsTheSession() {
        launch("stub_delete_blocked").use {
            signIn("123456")
            deleteAccount()
            compose.waitForTag("settings-action-error")
            compose.onNodeWithTag("settings-action-error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("delete-confirm").assertExists()
        }
    }

    @Test fun signOutFromSettingsReturnsToSignIn() {
        launch().use {
            signIn("123456")
            compose.waitForTag("open-settings")
            compose.tapSafely("open-settings")
            compose.waitForTag("settings-signout")
            compose.tapSafely("settings-signout")
            compose.waitForTag("role-donor")
        }
    }

    @Test fun partnerSignsInWithAPastedLinkAndSignsOut() {
        launch().use {
            compose.waitForTag("role-partner")
            compose.tapSafely("role-partner")
            compose.waitForTag("partner-apply")
            compose.enterText("partner-sign-in-email", "ada@acme.com")
            waitEnabled("partner-sign-in-submit")
            compose.tapSafely("partner-sign-in-submit")
            compose.waitForTag("partner-check-email")
            compose.enterText("partner-paste-link", "https://stub.invalid/partner/accept#token=goodpartner0123456789abcdefghijklmnop")
            waitEnabled("partner-paste-submit")
            compose.tapSafely("partner-paste-submit")
            compose.waitForTag("partner-committed")
            compose.onNodeWithTag("partner-organization").assertTextEquals("Acme Corp")
            compose.tapSafely("partner-tab-organization")
            compose.tapSafely("partner-sign-out")
            compose.waitForTag("role-partner")
        }
    }

    @Test fun aStoredPartnerSessionOpensThePortal() {
        launch("stub_partner_signed_in").use {
            compose.waitForTag("partner-committed")
            compose.tapSafely("partner-tab-organization")
            compose.waitForTag("partner-invite")
        }
    }

    @Test fun theAnalyticsQuestionIsAskedOnceAndAnsweringYesSendsAnEvent() {
        DebugBackend.resetRequestLog()
        launch("stub_analytics_unset").use {
            // The analytics question moved off Welcome: it is now a ModalBottomSheet shown ONCE after the
            // first sign-in. So sign in first, then expect analytics-choice, allow it, and confirm an event
            // is uploaded. Same copy/options/tags; only WHEN it appears changed.
            signIn("123456")
            compose.waitForTag("analytics-choice")
            compose.tapSafely("analytics-allow")
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("analytics-choice")).fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(15_000) { "POST /analytics/events" in DebugBackend.requestLog }
        }
    }

    @Test fun aTappedNotificationOpensItsScreenAndTheTokenIsRegistered() {
        DebugBackend.resetRequestLog()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).putExtra("stub_push", true)
            .putExtra("category", "STREAK_AT_RISK").putExtra("path", "/account/gifts")
        compose.launchReady(intent).use {
            compose.waitForTag("history-screen")
            compose.waitUntil(15_000) { "POST /donor/me/push-device" in DebugBackend.requestLog }
        }
    }
}
