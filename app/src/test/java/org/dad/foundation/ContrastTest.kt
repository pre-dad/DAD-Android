package org.dad.foundation

import org.dad.foundation.generated.DADTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    @Test fun contrastMatchesKnownReferenceValues() {
        assertTrue(contrast("ink", "card") > 15.0)
        assertEquals(21.0, ratio("#000000", "#ffffff"), 0.001)
    }

    @Test fun primaryButtonLabelMeetsAA() {
        // The primary button is a brand fill with an ink label in both light and dark.
        val ratio = contrast("ink", "brand")
        assertTrue("ink-on-brand ratio=$ratio", ratio >= 4.5)
    }

    @Test fun streakBarFillReadsOnPageBackground() {
        // The `ok-ink` outline is a redundant shape cue and the bar's text contentDescription carries
        // the state, so only the fill against the page background is asserted.
        assertTrue(contrast("paper", "ok") >= 4.5)
    }

    @Test fun brandTextOnPageBackgroundMeetsAAInBothThemes() {
        for (dark in listOf(false, true)) {
            val ratio = contrast(DadTheme.brandAccentToken(dark), backgroundToken(dark))
            assertTrue("dark=$dark ratio=$ratio", ratio >= 4.5)
        }
    }

    @Test fun lockedBadgeTextMeetsAAInBothThemes() {
        assertTrue(contrast("muted", "paper") >= 4.5)
        assertTrue(contrast("dkmut", "ink-deep") >= 4.5)
    }

    @Test fun atRiskBannerTextMeetsAA() {
        // The at-risk banner puts `warn` text on a `warn-wash` block; colour is not the only signal, but
        // the text itself must still read.
        assertTrue(contrast("warn", "warn-wash") >= 4.5)
        assertTrue(contrast("brand-ink", "warn-wash") >= 4.5)
        assertTrue(contrast("brand-ink", "card") >= 4.5)
    }

    @Test fun textOnTheInkSurfaceMeetsAA() {
        // The streak hero and the Welcome screen both sit on `ink`: titles in `paper`, low-emphasis
        // labels in `dkmut`, the Founding-Donor chip in `brand`.
        assertTrue(contrast("paper", "ink") >= 4.5)
        assertTrue(contrast("dkmut", "ink") >= 4.5)
        assertTrue(contrast("brand", "ink") >= 4.5)
    }

    @Test fun destructiveAndWashPairsMeetAA() {
        assertTrue(contrast("fail", "paper") >= 4.5)
        assertTrue(contrast("fail-line", "ink-deep") >= 4.5)
        assertTrue(contrast("ink", "ok-wash") >= 4.5)
        assertTrue(contrast("ok-ink", "ok-wash") >= 4.5)
        assertTrue(contrast("ink", "brand-wash") >= 4.5)
    }

    private fun backgroundToken(dark: Boolean): String = if (dark) "ink-deep" else "paper"

    /** WCAG 2.x contrast ratio (1.0..21.0) between two opaque `#rgb`/`#rrggbb` tokens, by token name. */
    private fun contrast(foreground: String, background: String): Double =
        ratio(DADTokens.color.getValue(foreground), DADTokens.color.getValue(background))

    private fun ratio(foregroundHex: String, backgroundHex: String): Double {
        val la = luminance(foregroundHex)
        val lb = luminance(backgroundHex)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val digits = hex.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        fun channel(i: Int): Double {
            val v = digits.substring(i, i + 2).toInt(16) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }
}
