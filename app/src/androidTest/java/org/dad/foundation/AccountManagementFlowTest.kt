package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Contact details, email change, the employer link and team, and ending sessions through the REAL
 * [WireAccountManagement], session and screens on the DEBUG-only scripted transport (`stub_backend`). Script:
 * company code `ACME42` links "Acme"; an email starting `taken` belongs to another account.
 */
class AccountManagementFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun openSettings(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = compose.launchReady(intent)
        compose.tapSafely("open-settings", 20_000)
        compose.waitForTag("settings-email")
        return scenario
    }

    @Test fun contactDetailsSaveANormalizedPhone() {
        openSettings().use {
            compose.waitForTag("details-save")
            compose.onNodeWithTag("details-save").assertIsNotEnabled()
            compose.enterText("details-phone", "(415) 555-0123", replace = true)
            compose.tapSafely("details-save")
            compose.waitForTag("details-saved")
            compose.waitForTagText("details-phone", "+14155550123")
        }
    }

    @Test fun emailChangeExplainsAConflictThenSends() {
        openSettings().use {
            compose.tapSafely("email-change-start")
            compose.enterText("email-change-input", "taken@example.org", replace = true)
            compose.tapSafely("email-change-submit")
            compose.waitForTag("email-change-error")
            val conflict = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.acct_that_address_cant_replace_your)
            compose.onNodeWithTag("email-change-error").assertTextContains(conflict)
            compose.enterText("email-change-input", "new@example.org", replace = true)
            compose.tapSafely("email-change-submit")
            compose.waitForTag("email-change-sent")
        }
    }

    @Test fun aCompanyCodeLinksAnEmployerThenTheDonorJoinsATeam() {
        openSettings().use {
            compose.enterText("employer-code", "WRONG", replace = true)
            compose.tapSafely("employer-enroll")
            compose.waitForTag("employer-error")
            compose.enterText("employer-code", "ACME42", replace = true)
            compose.tapSafely("employer-enroll")
            compose.waitForTag("employer-linked")
            compose.waitForTagGone("employer-error")
            compose.waitForTag("leaderboard-55555555-5555-4555-8555-555555555555")
            compose.enterText("employer-team-name", "Platform", replace = true)
            compose.tapSafely("employer-join-team")
            compose.waitForTagText("employer-team", "Your team: Platform")
        }
    }

    @Test fun endingAnotherSessionThenEverySessionSignsOut() {
        openSettings().use {
            compose.tapSafely("session-revoke-22222222-2222-4222-8222-222222222222")
            compose.waitForTag("sessions-none")
            compose.tapSafely("sessions-sign-out-everywhere")
            compose.waitForTag("sessions-sign-out-everywhere-confirm")
            compose.tapSafely("sessions-sign-out-everywhere-confirm")
            compose.waitForTag("role-donor")
        }
    }
}
