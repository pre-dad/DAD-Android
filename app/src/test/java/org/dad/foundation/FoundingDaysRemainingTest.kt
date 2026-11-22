package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FoundingDaysRemainingTest {
    private fun at(iso: String) = foundingDaysRemaining(Instant.parse(iso))

    @Test fun countsTheCurrentDayInFull() {
        assertEquals(1L, at("2026-12-31T05:00:00Z"))
        assertEquals(2L, at("2026-12-30T17:00:00Z"))
    }

    @Test fun staysOpenThroughTheLastSecondOfTheDeadlineDay() {
        assertEquals(1L, at("2027-01-01T04:59:59Z"))
    }

    @Test fun endsAtMidnightNewYork() {
        assertNull(at("2027-01-01T05:00:00Z"))
    }
}
