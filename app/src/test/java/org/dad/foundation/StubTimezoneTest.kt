package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StubTimezoneTest {
    @Test fun correctionsPersistAcrossOmittedWritesAndResetExplicitly() {
        val account = StubAccount()
        fun save(body: String) = account.answer("POST", "/donor/me/profile", body) { "{}" }.first
        assertEquals(200, save("""{"zip":"78701","timezone_override":"America/Phoenix"}"""))
        assertEquals("America/Phoenix", account.timezoneOverride)
        assertEquals(200, save("""{"zip":""}"""))
        assertEquals("America/Phoenix", account.timezoneOverride)
        assertEquals(200, save("""{"zip":"","timezone_override":""}"""))
        assertNull(account.timezoneOverride)
        assertEquals(422, save("""{"timezone_override":"Invalid/Zone"}"""))
    }
}
