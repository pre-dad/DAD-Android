package org.dad.foundation

import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallReferrerIntakeTest {
    private class FakeService : LinkAttributionService {
        val referrals = mutableListOf<Pair<String, Boolean>>()
        val companies = mutableListOf<String>()
        override suspend fun captureReferral(code: String, inPerson: Boolean, token: String?): ReferralCapture {
            referrals += code to inPerson
            return ReferralCapture("t".repeat(43), "Alex R")
        }
        override suspend fun captureCompany(code: String, token: String?): String? { companies += code; return "c".repeat(43) }
        override suspend fun bindCompanyTouch(token: String) = true
    }

    private class FakeReferrer(private val answer: suspend () -> String?) : InstallReferrer {
        var calls = 0
        override suspend fun fetch(): String? { calls++; return answer() }
    }

    private class Rig(referrer: InstallReferrer, val ledger: InstallReferrerLedger = MemoryInstallReferrerLedger(), timeoutMs: Long = 5_000) {
        val service = FakeService()
        val analytics = RecordingAnalyticsEmitter()
        val intake = InstallReferrerIntake(referrer, ledger, LinkAttribution(service, MemoryLinkTokenStore(), analytics), timeoutMs)
    }

    @Test fun aValidInviteIsCapturedOnceAsALinkTouchAndRecordedAsRead() = runTest {
        val referrer = FakeReferrer { "invite=ALEX42&utm_source=instagram" }
        val rig = Rig(referrer)
        assertEquals("Alex R", rig.intake.run())
        assertEquals(listOf("ALEX42" to false), rig.service.referrals)
        assertEquals(listOf(AnalyticsEvent.AttributionCaptured(AttributionSource.LINK)), rig.analytics.events)
        assertTrue(rig.ledger.wasRead())
        assertNull("a second launch captures nothing", rig.intake.run())
        assertEquals(1, referrer.calls)
        assertEquals(1, rig.service.referrals.size)
    }

    @Test fun anIrlAndACompanyCodeTakeTheirOwnPaths() = runTest {
        val rig = Rig(FakeReferrer { "irl=TABLE7&company=ACME42" })
        rig.intake.run()
        assertEquals(listOf("TABLE7" to true), rig.service.referrals)
        assertEquals(listOf("ACME42"), rig.service.companies)
    }

    @Test fun noReferrerCapturesNothingButIsStillMarkedRead() = runTest {
        for (answer in listOf<String?>(null, "", "   ", "utm_source=google-play&utm_medium=organic")) {
            val rig = Rig(FakeReferrer { answer })
            assertNull(rig.intake.run())
            assertTrue(rig.service.referrals.isEmpty() && rig.service.companies.isEmpty())
            assertTrue(rig.analytics.events.isEmpty())
            assertTrue(rig.ledger.wasRead())
        }
    }

    @Test fun malformedInputIsNeverTrusted() = runTest {
        val tooLong = "invite=" + "A".repeat(65)
        val hostile = "invite=" + "..%2F..%2Fadmin&irl=a%20b&company=%3Cscript%3E"
        val huge = "invite=ALEX42&pad=" + "x".repeat(InstallReferrerIntake.MAX_REFERRER_LENGTH)
        for (answer in listOf(tooLong, hostile, huge, "not a referrer string", "invite")) {
            val rig = Rig(FakeReferrer { answer })
            assertNull(rig.intake.run())
            assertTrue("$answer", rig.service.referrals.isEmpty() && rig.service.companies.isEmpty())
            assertTrue(rig.ledger.wasRead())
        }
    }

    @Test fun anAlreadyReadReferrerIsNotAskedAgain() = runTest {
        val referrer = FakeReferrer { "invite=ALEX42" }
        val rig = Rig(referrer, MemoryInstallReferrerLedger(read = true))
        assertNull(rig.intake.run())
        assertEquals(0, referrer.calls)
        assertTrue(rig.service.referrals.isEmpty())
    }

    @Test fun aClientThatFailsCountsAsNone() = runTest {
        val rig = Rig(FakeReferrer { throw IllegalStateException("boom") })
        assertNull(rig.intake.run())
        assertTrue(rig.ledger.wasRead())
        assertTrue(rig.service.referrals.isEmpty())
    }

    @Test fun aReferrerThatNeverAnswersTimesOutAsNone() = runTest {
        val rig = Rig(FakeReferrer { awaitCancellation() }, timeoutMs = 5_000)
        val result = async { rig.intake.run() }
        advanceTimeBy(4_999); runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertNull(result.await())
        assertTrue(rig.ledger.wasRead())
        assertTrue(rig.service.referrals.isEmpty())
    }

    @Test fun cancellingBeforeTheAnswerLeavesTheReferrerUnreadAndCapturesNothing() = runTest {
        val gate = CompletableDeferred<String?>()
        val rig = Rig(FakeReferrer { gate.await() })
        val job = async { rig.intake.run() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(rig.ledger.wasRead())
        assertTrue(rig.service.referrals.isEmpty())
        // The next launch reads it.
        gate.complete("invite=ALEX42")
        assertEquals("Alex R", Rig(FakeReferrer { "invite=ALEX42" }, rig.ledger).intake.run())
    }

    // --- The Play client's response handling ---

    private class FakeConnection(
        private val response: Int?, private val referrer: String? = "invite=ALEX42",
        private val readFails: Boolean = false, private val startFails: Boolean = false,
    ) : ReferrerConnection {
        var ended = 0
        var read = 0
        override fun start(onFinished: (Int) -> Unit) {
            if (startFails) throw SecurityException("bind refused")
            response?.let(onFinished)
        }
        override fun read(): String? { read++; if (readFails) throw IllegalStateException("remote"); return referrer }
        override fun end() { ended++ }
    }

    @Test fun anOkConnectionReturnsTheReferrerAndAlwaysEnds() = runTest {
        val connection = FakeConnection(InstallReferrerResponse.OK)
        val client = PlayInstallReferrer({ connection }, kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals("invite=ALEX42", client.fetch())
        assertEquals(1, connection.ended)
    }

    @Test fun everyNonOkResponseMapsToNone() = runTest {
        val codes = listOf(
            InstallReferrerResponse.SERVICE_UNAVAILABLE, InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
            InstallReferrerResponse.DEVELOPER_ERROR, InstallReferrerResponse.SERVICE_DISCONNECTED, InstallReferrerResponse.PERMISSION_ERROR,
        )
        for (code in codes) {
            val connection = FakeConnection(code)
            assertNull("code $code", PlayInstallReferrer({ connection }, kotlinx.coroutines.Dispatchers.Unconfined).fetch())
            assertEquals(0, connection.read)
            assertEquals(1, connection.ended)
        }
    }

    @Test fun aRefusedBindOrAFailedReadMapsToNone() = runTest {
        val refused = FakeConnection(null, startFails = true)
        assertNull(PlayInstallReferrer({ refused }, kotlinx.coroutines.Dispatchers.Unconfined).fetch())
        assertEquals(1, refused.ended)
        val unreadable = FakeConnection(InstallReferrerResponse.OK, readFails = true)
        assertNull(PlayInstallReferrer({ unreadable }, kotlinx.coroutines.Dispatchers.Unconfined).fetch())
        assertEquals(1, unreadable.ended)
        assertNull(PlayInstallReferrer({ throw IllegalStateException("no client") }, kotlinx.coroutines.Dispatchers.Unconfined).fetch())
    }

    @Test fun aConnectionThatNeverFinishesTimesOutAndEnds() = runTest {
        val connection = FakeConnection(null)
        val result = async { PlayInstallReferrer({ connection }, kotlinx.coroutines.Dispatchers.Unconfined, timeoutMs = 3_000).fetch() }
        advanceTimeBy(3_001); runCurrent()
        assertNull(result.await())
        assertEquals(0, connection.read)
        assertEquals(1, connection.ended)
    }

    @Test fun cancellingTheFetchEndsTheConnection() = runTest {
        val connection = FakeConnection(null)
        val job = async { PlayInstallReferrer({ connection }, kotlinx.coroutines.Dispatchers.Unconfined).fetch() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, connection.ended)
    }
}
