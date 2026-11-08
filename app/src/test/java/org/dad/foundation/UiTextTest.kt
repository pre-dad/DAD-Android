package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    @Test fun resourceTextIsComparedByIdAndArguments() {
        assertEquals(UiText.res(R.string.partner_invitation_sent_to, "a@b.co"), UiText.res(R.string.partner_invitation_sent_to, "a@b.co"))
        assertNotEquals(UiText.res(R.string.partner_invitation_sent_to, "a@b.co"), UiText.res(R.string.partner_invitation_sent_to, "c@d.co"))
        assertNotEquals(UiText.res(R.string.common_cancel), UiText.res(R.string.common_back))
        assertEquals(UiText.plain("Alex"), UiText.plain("Alex"))
    }

    @Test fun formatArgumentsFillThePlaceholders() {
        assertEquals("Invitation sent to a@b.co.", UiText.res(R.string.partner_invitation_sent_to, "a@b.co").text())
        assertEquals("Shown as Alex C.", UiText.res(R.string.network_shown_as, "Alex C.").text())
    }

    @Test fun pluralsPickTheFormForTheQuantity() {
        assertEquals("1 spot left", UiText.plural(R.plurals.events_spots_left, 1).text())
        assertEquals("2 spots left", UiText.plural(R.plurals.events_spots_left, 2).text())
        assertEquals("1 more to Circle of 10", UiText.plural(R.plurals.network_milestone_remaining, 1, 1, "Circle of 10").text())
        assertEquals("4 more to Circle of 10", UiText.plural(R.plurals.network_milestone_remaining, 4, 4, "Circle of 10").text())
    }

    @Test fun anArgumentThatIsItselfUiTextIsResolvedFirst() {
        val wait = UiText.plural(R.plurals.appsubmit_wait_minutes, 2)
        assertEquals("Too many attempts. Please wait 2 minutes and try again.", UiText.res(R.string.appsubmit_too_many_attempts_wait, wait).text())
    }

    @Test fun curlyApostrophesAndLiteralPercentSignsSurviveTheResourceFile() {
        assertEquals("You’re going", UiText.res(R.string.events_youre_going).text())
        assertEquals("Top 2% nationally", UiText.res(R.string.eng_top_percent_nationally, 2).text())
    }

    @Test fun everyStringResourceParsesAndHasNoStraightApostropheInAWord() {
        assertTrue(TestStrings.stringKeys.size > 500)
        val straight = TestStrings.stringKeys.filter { Regex("[A-Za-z]'[A-Za-z]").containsMatchIn(TestStrings.string(it)) }
        assertEquals(emptyList<String>(), straight)
    }
}
