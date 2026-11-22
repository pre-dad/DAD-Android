package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEventTest {
    @Test fun analyticsEventsCarryExpectedNames() {
        assertEquals("invite_shared", AnalyticsEvent.InviteShared("sms").name)
        assertEquals("irl_code_shown", AnalyticsEvent.IrlCodeShown.name)
        assertEquals("referral_converted", AnalyticsEvent.ReferralConverted.name)
    }

    /** Pins each event's payload to DAD-Main's ingest allow-list (analytics.go eventProperties). */
    @Test fun analyticsPayloadsMatchTheIngestAllowList() {
        assertEquals(mapOf("source" to "link"), AnalyticsEvent.AttributionCaptured(AttributionSource.LINK).properties)
        assertEquals(mapOf("source" to "IRL"), AnalyticsEvent.AttributionCaptured(AttributionSource.IRL).properties)
        assertEquals(mapOf("source" to "company"), AnalyticsEvent.AttributionCaptured(AttributionSource.COMPANY).properties)
        assertEquals(mapOf("channel" to "sms"), AnalyticsEvent.InviteShared("sms").properties)
        assertTrue(AnalyticsEvent.IrlCodeShown.properties.isEmpty())
        assertTrue(AnalyticsEvent.ReferralConverted.properties.isEmpty())
    }
}
