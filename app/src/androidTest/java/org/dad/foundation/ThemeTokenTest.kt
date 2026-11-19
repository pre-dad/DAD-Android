package org.dad.foundation

import androidx.compose.ui.graphics.Color
import org.dad.foundation.generated.DADTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeTokenTest {
    @Test fun sharedPaletteResolvesWithoutFallbackColors() {
        assertEquals(Color.White, DadTheme.color("card"))
        for (name in DADTokens.color.keys) {
            assertNotEquals("Unresolved token: $name", Color.Magenta, DadTheme.color(name))
        }
    }
}
