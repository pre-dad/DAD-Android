package org.dad.foundation

import java.time.OffsetDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FriendlyDateZoneTest {
    private val original = TimeZone.getDefault()
    @Before fun setUp() = TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
    @After fun tearDown() = TimeZone.setDefault(original)

    @Test fun anInstantIsShownOnTheDeviceCalendarDayNotTheWireOffsetDay() {
        assertEquals("January 1, 2026", friendlyDate(OffsetDateTime.parse("2026-01-02T02:30:00Z")))
        assertEquals("January 1, 2026", friendlyDate(OffsetDateTime.parse("2026-01-02T05:30:00+05:00")))
    }
}
