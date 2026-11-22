package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Settings journey. Launched via the `preview_settings` extra. Covers: the screen
 * renders profile + toggles, the delete-account confirm gate stays disabled until the exact word
 * is typed (store-mandated, §6.12), and sign-out leaves the shell. All inline (no OS dialogs).
 * The settings column is long and scrollable, so controls are scrolled into view before use.
 */
class SettingsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("preview_settings", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return compose.launchReady(intent)
    }

    @Test fun settingsRendersProfileAndToggles() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("settings-home")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings-email").assertIsDisplayed()
            // A push toggle and the ranking switch are further down the scroll — scroll to them.
            compose.onNodeWithTag("pref-push_streak_at_risk").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("ranking-toggle").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun rankingSwitchFlipsAndPersistsThroughTheModel() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("ranking-toggle")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ranking-toggle").performScrollTo().assertIsOn()
            compose.tapSafely("ranking-toggle")
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ranking-toggle") and isOff()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ranking-toggle").assertIsOff()
        }
    }

    @Test fun deleteGateStaysDisabledUntilConfirmWordTyped() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("settings-home")).fetchSemanticsNodes().isNotEmpty() }
            compose.tapSafely("delete-start")
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("delete-confirm")).fetchSemanticsNodes().isNotEmpty() }
            // Disabled with no text, and still disabled for the wrong letter case.
            compose.onNodeWithTag("delete-confirm").performScrollTo().assertIsNotEnabled()
            compose.enterText("delete-input", "delete")
            compose.onNodeWithTag("delete-confirm").performScrollTo().assertIsNotEnabled()
            compose.enterText("delete-input", "DELETE", replace = true)
            // Enabled once the exact word is present.
            compose.onNodeWithTag("delete-confirm").performScrollTo().assertIsEnabled()
        }
    }

    @Test fun signOutLeavesTheShell() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("settings-signout")).fetchSemanticsNodes().isNotEmpty() }
            compose.tapSafely("settings-signout")
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("settings-signed-out")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings-signed-out").assertIsDisplayed()
        }
    }

    @Test fun volunteerInterestsLoadAddRemoveAndAnnounceThemselves() {
        launch().use {
            compose.waitForTag("interests-section")
            // The saved interest is listed, with a control TalkBack reads as "Remove <interest>".
            compose.onNodeWithTag("interest-row-Park cleanups").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("interest-remove-Park cleanups").performScrollTo().assertContentDescriptionEquals("Remove Park cleanups")
            compose.onNodeWithTag("interest-add").performScrollTo().assertIsNotEnabled()

            compose.enterText("interest-input", "  Food bank ")
            compose.onNodeWithTag("interest-add").performScrollTo().assertIsEnabled()
            compose.tapSafely("interest-add")
            compose.waitForTag("interest-row-Food bank")
            compose.onNodeWithTag("interest-input").performScrollTo().assert(SemanticsMatcher("an empty field") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" })

            // A repeat (any letter case) is refused with a message, nothing is added.
            compose.enterText("interest-input", "food BANK")
            compose.tapSafely("interest-add")
            compose.waitForTag("interest-notice")
            compose.onNodeWithTag("interest-notice").assertTextContains("already on your list", substring = true)

            compose.tapSafely("interest-remove-Park cleanups")
            compose.waitForTagGone("interest-row-Park cleanups")
            compose.onNodeWithTag("interest-row-Food bank").assertIsDisplayed()
        }
    }
}
