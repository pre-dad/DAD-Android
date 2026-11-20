package org.dad.foundation

import org.dad.foundation.generated.DADTokens
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    @Test fun contrastMatchesKnownReferenceValues() {
        assertTrue(contrast("ink", "card") > 15.0)
        assertTrue(contrast("brand", "paper") < 3.3)
    }

    @Test fun primaryButtonLabelMeetsAA() {
        // The web-style primary (DadPrimaryButton + Material primary) is a brand fill with an ink
        // label in BOTH light and dark: ink-on-brand = 5.11:1, clearing WCAG AA (>= 4.5).
        val ratio = contrast("ink", "brand")
        assertTrue("ink-on-brand ratio=$ratio", ratio >= 4.5)
    }

    @Test fun streakBarFillReadsOnPageBackground() {
        // The streak bar fills with teal `ok` (orange is identity, not progress). Pin the fill against
        // the light page background it reads on: paper-on-ok = 4.90:1 (AA). The `ok-ink` outline is a
        // redundant shape cue (ok-ink-on-ok is only 1.44:1), NOT the accessible signal — the bar's text
        // contentDescription ("Streak: N of M days") carries state — so it is intentionally not asserted.
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
        // Home's at-risk banner: `warn` text + warning glyph on a `warn-wash` block. warn-on-warn-wash =
        // 4.91:1 (AA). Colour is not the only signal (glyph + text), but the text itself must still read.
        assertTrue(contrast("warn", "warn-wash") >= 4.5)
        assertTrue(contrast("brand-ink", "warn-wash") >= 4.5)
        assertTrue(contrast("brand-ink", "card") >= 4.5)
    }

    @Test fun streakHeroTextOnInkMeetsAA() {
        // The streak hero card sits on the `ink` surface. Every foreground it uses clears AA on ink:
        // the day count / rank lines in `paper` (16.29:1), the low-emphasis labels in `dkmut` (9.77:1),
        // and the Founding-Donor chip in `brand` (5.11:1).
        assertTrue(contrast("paper", "ink") >= 4.5)
        assertTrue(contrast("dkmut", "ink") >= 4.5)
        assertTrue(contrast("brand", "ink") >= 4.5)
    }

    @Test fun welcomeTextOnInkMeetsAA() {
        // Welcome (RoleSelect) is on the `ink` background. Title in `paper` (16.29:1); eyebrow, sub and the
        // Founding-Donor line in `dkmut` (9.77:1); the "Give $1 a day" primary is a `brand` fill with an
        // `ink` label (5.11:1). Each foreground clears WCAG AA on its background.
        assertTrue(contrast("paper", "ink") >= 4.5)
        assertTrue(contrast("dkmut", "ink") >= 4.5)
        assertTrue(contrast("ink", "brand") >= 4.5)
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
    private fun contrast(foreground: String, background: String): Double {
        val la = luminance(DADTokens.color.getValue(foreground))
        val lb = luminance(DADTokens.color.getValue(background))
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
