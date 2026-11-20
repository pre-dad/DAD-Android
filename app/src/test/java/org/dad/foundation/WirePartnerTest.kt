package org.dad.foundation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real partner conformers over a scripted transport (Retrofit + OkHttp run for real). */
class WirePartnerTest {
    @Test fun initiativeDetailKeepsCountySpendAndVerificationDates() = runTest {
        val http = ScriptedHttp { 200 to """{"initiatives":[{"id":"i1","title":"Water","summary":"Wells","status":"ACTIVE","region":"West","county_count":6,"committed_minor":2000000,"deployed_minor":1200000,"metrics":[{"label":"People","value":8400,"unit":"PEOPLE","verified_on":"2026-10-20"}],"spend":[{"category":"Water","amount_minor":1200000}],"updated_at":"2026-10-28T10:00:00Z"}]}""" }
        val session = PartnerSession(RecordingStore()).also { it.install("PB") }
        val initiative = WirePartner(TEST_ORIGIN, session, http.client()).initiatives().single()
        assertEquals(6, initiative.countyCount)
        assertEquals(0.6f, initiative.deployedFraction!!, 0.001f)
        assertEquals(1200000L, initiative.spend.single().amountMinor)
        assertEquals(java.time.LocalDate.of(2026, 10, 20), initiative.metrics.single().verifiedOn)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-28T10:00:00Z"), initiative.updatedAt)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun partnerRequestsCannotOutliveTheirSession() = runTest {
        for (rejected in listOf(false, true)) {
            val store = RecordingStore()
            val session = PartnerSession(store).also { it.install("old") }
            val gate = CompletableDeferred<Unit>()
            val request = async { runCatching { session.authorized {
                gate.await()
                if (rejected) throw AccountFailure.SignedOut
                "old private data"
            } } }
            runCurrent()
            session.install("new")
            gate.complete(Unit)
            assertEquals(AccountFailure.SignedOut, request.await().exceptionOrNull())
            assertEquals("new", session.token())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun partnerSignInCannotRestoreASignedOutSession() = runTest {
        val store = RecordingStore()
        val session = PartnerSession(store)
        val gate = CompletableDeferred<String>()
        val request = async { runCatching { session.establish { gate.await() } } }
        runCurrent()
        session.clear()
        gate.complete("late")
        assertEquals(AccountFailure.SignedOut, request.await().exceptionOrNull())
        assertNull(store.value)
    }

    @Test fun cancelledPartnerSignInInstallsNothing() = runTest {
        val store = RecordingStore()
        val session = PartnerSession(store)
        val request = launch { session.establish { currentCoroutineContext().cancel(); "late" } }
        request.join()
        assertTrue(request.isCancelled)
        assertNull(store.value)
    }

    private val overviewJson = """{"user_id":"u1","organization":"Acme","description":"","role":"ADMIN","company_code":"ACME",""" +
        """"committed_minor":500000,"deployed_minor":300000,"deployed_percent":60,"people_reached":1200,"employees_enrolled":null,""" +
        """"workforce_size":120,"enrolled_percent":null,"updated_at":"2026-10-28T10:00:00Z","has_logo":false,""" +
        """"committed_verified":true,"deployed_verified":false,"people_reached_verified":true}"""

    @Test fun acceptKeepsTheBearerAndPortalCallsSendIt() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/partner/accept-invite" -> 200 to """{"session":"PB"}"""
                "/donor/partner/overview" -> 200 to overviewJson
                else -> 404 to "{}"
            }
        }
        val store = RecordingStore()
        val session = PartnerSession(store)
        WirePartnerAuth(TEST_ORIGIN, session, http.client()).accept("INV")
        assertEquals("""{"invite":"INV"}""", http.requests.first().body)
        assertNull(http.requests.first().headers["authorization"])
        assertEquals("", store.value?.refresh)
        val overview = WirePartner(TEST_ORIGIN, session, http.client()).overview()
        assertEquals("Bearer PB", http.requests.last().headers["authorization"])
        assertEquals("native", http.requests.last().headers["x-dad-client"])
        assertNull(overview.deployed.label)
        assertNull(overview.enrolledLabel)
        assertEquals("u1", overview.userId)
    }

    @Test fun aUsedLinkAndABlockedPartnerAreTheirOwnFailures() = runTest {
        mapOf(422 to AccountFailure.InvalidInput, 403 to AccountFailure.Forbidden, 429 to AccountFailure.RateLimited).forEach { (status, expected) ->
            val wire = WirePartnerAuth(TEST_ORIGIN, PartnerSession(RecordingStore()), ScriptedHttp { status to "{}" }.client())
            assertEquals("$status", expected, runCatching { wire.accept("INV") }.exceptionOrNull())
        }
    }

    @Test fun aRejectedSessionIsForgotten() = runTest {
        val store = RecordingStore()
        val session = PartnerSession(store).also { it.install("PB") }
        val failure = runCatching { WirePartner(TEST_ORIGIN, session, ScriptedHttp { 401 to "{}" }.client()).users() }.exceptionOrNull()
        assertEquals(AccountFailure.SignedOut, failure)
        assertNull(store.value)
        assertFalse(session.isSignedIn)
    }

    @Test fun inviteRemoveAndLogoBodies() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/partner/users" -> 200 to """{"invited":true}"""
                "/donor/partner/users/33333333-3333-4333-8333-333333333333/remove" -> 200 to """{"removed":true}"""
                else -> 200 to """{"saved":true}"""
            }
        }
        val wire = WirePartner(TEST_ORIGIN, PartnerSession(RecordingStore()).also { it.install("PB") }, http.client())
        wire.inviteUser("b@x.com", "Bea", PartnerRole.VIEWER)
        wire.removeUser("33333333-3333-4333-8333-333333333333")
        wire.saveLogo(byteArrayOf(1, 2, 3))
        wire.saveLogo(null)
        assertEquals("""{"email":"b@x.com","name":"Bea","role":"VIEWER"}""", http.requests[0].body)
        assertEquals("/donor/partner/users/33333333-3333-4333-8333-333333333333/remove", http.requests[1].path)
        assertEquals("""{"data_url":"data:image/png;base64,AQID"}""", http.requests[2].body)
        assertEquals("""{"data_url":""}""", http.requests[3].body)
    }

    @Test fun aMissingLogoIsNoneAndAnAgedOutReportIsNotFound() = runTest {
        val wire = WirePartner(TEST_ORIGIN, PartnerSession(RecordingStore()).also { it.install("PB") }, ScriptedHttp { 404 to "{}" }.client())
        assertNull(wire.logo())
        assertEquals(AccountFailure.NotFound, runCatching { wire.retainedReport("44444444-4444-4444-8444-444444444444") }.exceptionOrNull())
    }

    @Test fun aMalformedIdNeverBecomesAPathSegment() = runTest {
        val http = ScriptedHttp { 200 to "{}" }
        val wire = WirePartner(TEST_ORIGIN, PartnerSession(RecordingStore()).also { it.install("PB") }, http.client())
        listOf("u2", "..", "../logout").forEach {
            assertEquals(it, AccountFailure.InvalidInput, runCatching { wire.removeUser(it) }.exceptionOrNull())
            assertEquals(it, AccountFailure.InvalidInput, runCatching { wire.retainedReport(it) }.exceptionOrNull())
        }
        assertEquals(0, http.requests.size)
    }

    @Test fun signOutForgetsTheSessionEvenWhenTheServerIsDown() = runTest {
        val store = RecordingStore()
        val session = PartnerSession(store).also { it.install("PB") }
        WirePartner(TEST_ORIGIN, session, ScriptedHttp { 503 to "{}" }.client()).signOut()
        assertNull(store.value)
        assertFalse(session.isSignedIn)
    }
}
