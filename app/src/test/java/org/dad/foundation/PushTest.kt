package org.dad.foundation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushTest {
    @Test fun theDonorRegistersAnAndroidTokenWithNoEnvironment() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/me/push-device" -> 200 to """{"registered":true,"platform":"ANDROID","environment":null}"""
                else -> 404 to "{}"
            }
        }
        val session = AccountSession(WireAPI(TEST_ORIGIN, http.client()), InMemoryCredentialStore(Credentials("access-0", "refresh-0", Long.MAX_VALUE)), backgroundScope)
        val push = WireDonorPush(ProductClient(TEST_ORIGIN, session, http.client()))
        push.register("fcm-token-0123456789-0123456789-0123")
        assertEquals("""{"platform":"ANDROID","token":"fcm-token-0123456789-0123456789-0123"}""", http.requests[0].body)
        assertEquals("POST", http.requests[0].method)
        assertEquals("/donor/me/push-device", http.requests[0].path)
        assertEquals("Bearer access-0", http.requests[0].headers["authorization"])
        assertEquals(1, http.requests.size)
    }

    @Test fun onlyAWellFormedCategoryIsATap() {
        assertEquals(PushTap("STREAK_AT_RISK", "/account/dashboard"), PushTap.from("STREAK_AT_RISK", "/account/dashboard"))
        assertEquals(PushTap("REFERRAL_CONVERTED", null), PushTap.from("REFERRAL_CONVERTED", null))
        assertNull(PushTap.from(null, "/account/gifts"))
        assertNull(PushTap.from("streak; drop", "/account/gifts"))
        assertEquals(PushTap("STREAK_AT_RISK", null), PushTap.from("STREAK_AT_RISK", "/" + "a".repeat(300)))
        assertEquals("/" + "a".repeat(255), PushTap.from("STREAK_AT_RISK", "/" + "a".repeat(255))?.path)
        assertNull(PushTap.from("", null))
    }
}
