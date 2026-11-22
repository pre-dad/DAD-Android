package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRulesTest {
    @Test fun zipAcceptsFiveDigitsZipPlusFourOrEmpty() {
        listOf("78701", "78701-1234", "", " 78701 ").forEach { assertTrue(it, ProfileRules.isAcceptableZip(it)) }
        listOf("7870", "787011", "78701-12", "abcde").forEach { assertFalse(it, ProfileRules.isAcceptableZip(it)) }
    }

    @Test fun phoneNormalizesTheWaysPeopleTypeAUsNumber() {
        assertEquals("+14155550123", ProfileRules.e164("(415) 555-0123"))
        assertEquals("+14155550123", ProfileRules.e164("1 415 555 0123"))
        assertEquals("+442079460958", ProfileRules.e164("+44 20 7946 0958"))
        assertEquals("", ProfileRules.e164(""))
        assertNull(ProfileRules.e164("555-0123"))
        assertNull(ProfileRules.e164("+0123"))
        assertNull(ProfileRules.e164("call me"))
    }

    @Test fun onlyAGoogleWalletSaveLinkIsOpened() {
        assertTrue(ProfileRules.isGoogleWalletSaveUrl("https://pay.google.com/gp/v/save/eyJhbGciOi"))
        listOf("http://pay.google.com/gp/v/save/x", "https://pay.google.com.evil.test/gp/v/save/x", "https://pay.google.com/other",
            "https://user@pay.google.com/gp/v/save/x", "https://pay.google.com:8443/gp/v/save/x", "not a url")
            .forEach { assertFalse(it, ProfileRules.isGoogleWalletSaveUrl(it)) }
    }
}
