package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun impactUnknownRendersDashNotZero() {
        assertEquals("--", ImpactTotals(givenMinor = null, initiativesFunded = 0, eventsVolunteered = 0).givenLabel)
        assertEquals("$1,240", ImpactTotals(givenMinor = 124_000, initiativesFunded = 3, eventsVolunteered = 2).givenLabel)
        assertEquals("$12.34", ImpactTotals(givenMinor = 1234, initiativesFunded = 0, eventsVolunteered = 0).givenLabel)
    }

    @Test fun rankingOptedOutShowsNothing() {
        val out = RankingStanding.optedOut()
        assertTrue(out.optedOut)
        assertNull(out.nationalLabel)
        assertNull(out.cityLabel)
    }

    @Test fun unrankedIsNotTheSameAsOptedOut() {
        // No figures yet (e.g. a new donor) must not be reported as an opt-out.
        val unranked = RankingStanding(nationalPercentile = null, city = null, cityRank = null)
        assertFalse(unranked.optedOut)
        assertNull(unranked.nationalLabel)
        assertNull(unranked.cityLabel)
    }

    @Test fun optedOutHidesFiguresEvenIfTheServerSentThem() {
        val standing = RankingStanding(2, "Austin", 4, optedOut = true)
        assertTrue(standing.optedOut)
        assertNull(standing.nationalLabel)
        assertNull(standing.cityLabel)
    }

    @Test fun dashboardFlagDrivesOptOutNotMissingFigures() {
        val unranked = RankingStanding.fromDashboard(null, null, null, rankingsOptedOut = false)
        assertFalse(unranked.optedOut) // opted in but not yet ranked
        val out = RankingStanding.fromDashboard(2, "Austin", 4, rankingsOptedOut = true)
        assertTrue(out.optedOut) // hides figures even if the server sent them
        assertNull(out.nationalLabel)
        assertNull(out.cityLabel)
        val ranked = RankingStanding.fromDashboard(2, "Austin", 4, rankingsOptedOut = false)
        assertEquals("#4 in Austin", ranked.cityLabel.textOrNull())
    }

    @Test fun rankingLabelsWhenOptedIn() {
        val standing = RankingStanding(nationalPercentile = 2, city = "Austin", cityRank = 4)
        assertFalse(standing.optedOut)
        // The server value is already the top bucket: 2 reads "Top 2%", not "Top 98%".
        assertEquals("Top 2% nationally", standing.nationalLabel.textOrNull())
        assertEquals("Top 1% nationally", RankingStanding(1, null, null).nationalLabel.textOrNull())
        assertEquals("Top 50% nationally", RankingStanding(50, null, null).nationalLabel.textOrNull())
        assertEquals("Top 98% nationally", RankingStanding(98, null, null).nationalLabel.textOrNull())
        assertEquals("#4 in Austin", standing.cityLabel.textOrNull())
    }

    @Test fun topPercentileNeverReadsZero() {
        assertEquals("Top 1% nationally", RankingStanding(0, null, null).nationalLabel.textOrNull())
        assertEquals("Top 1% nationally", RankingStanding(-3, null, null).nationalLabel.textOrNull())
    }

    @Test fun spendTotalsFormat() {
        val spend = InitiativeSpend(totalMinor = 500_000, categories = listOf(SpendCategory("Food", 300_000), SpendCategory("Housing", 200_000)))
        assertEquals("$5,000", spend.totalLabel)
        assertEquals("$3,000", spend.categories.first().amountLabel)
    }

    @Test fun topPercentileNeverReadsAboveOneHundred() {
        assertEquals("Top 100% nationally", RankingStanding(120, null, null).nationalLabel.textOrNull())
    }

    private class FakeEngagementService(
        var optedIn: Boolean = true,
        var failLoad: Throwable? = null,
        var failToggle: Boolean = false,
        var failName: Throwable? = null,
        var needsName: Boolean = false,
    ) : EngagementService {
        var loads = 0
        var cancelOnToggle = false
        var gate: CompletableDeferred<Unit>? = null
        private fun ranking() = if (optedIn) RankingStanding(2, "Austin", 4) else RankingStanding.optedOut()
        override suspend fun snapshot(): EngagementSnapshot {
            loads++
            failLoad?.let { throw it }
            val rankingAtRead = ranking()
            gate?.await()
            return EngagementSnapshot(
                impact = ImpactTotals(124_000, 3, 2),
                badges = BadgeGrid(listOf(Badge("F", "Founding Donor", "rule", UiText.plain("perk"), earned = true, earnedAt = null))),
                ranking = rankingAtRead,
                network = NetworkView(5, 348_000, null, emptyList(), 2),
                referral = if (needsName) null else ReferralInfo("ALEX", 2, 1, emptyList()),
                needsPublicName = needsName,
            )
        }
        override suspend fun setRankingOptIn(optIn: Boolean): RankingStanding {
            if (cancelOnToggle) throw CancellationException("cancelled")
            if (failToggle) throw AccountFailure.Unavailable
            optedIn = optIn
            return ranking()
        }
        override suspend fun choosePublicName(name: String) {
            failName?.let { throw it }
            needsName = false
        }
        override suspend fun initiativeSpend(id: String) = InitiativeSpend(1_000, listOf(SpendCategory("Food", 1_000)))
    }

    @Test fun loadReadySetsSnapshot() = runTest {
        val model = EngagementModel(FakeEngagementService())
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertEquals("$1,240", model.snapshot?.impact?.givenLabel)
        assertFalse(model.snapshot!!.ranking!!.optedOut)
    }

    @Test fun loadFailureSurfacesFailedPhase() = runTest {
        val model = EngagementModel(FakeEngagementService(failLoad = AccountFailure.Unavailable))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is EngagementPhase.Failed)
        assertFalse(model.signedOut)
    }

    @Test fun signedOutLoadRaisesTheSignedOutFlag() = runTest {
        val model = EngagementModel(FakeEngagementService(failLoad = AccountFailure.SignedOut))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.signedOut)
    }

    @Test fun aFailedRefreshKeepsTheContentAndShowsANotice() = runTest {
        val service = FakeEngagementService()
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        service.failLoad = AccountFailure.Unavailable
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertNotNull(model.snapshot)
        assertNotNull(model.refreshNotice)
    }

    @Test fun aCancelledRefreshKeepsThePriorState() = runTest {
        val service = FakeEngagementService()
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        service.failLoad = CancellationException("cancelled")
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertNotNull(model.snapshot)
        assertNull(model.refreshNotice)
    }

    @Test fun optOutReplacesRankingInPlace() = runTest {
        val model = EngagementModel(FakeEngagementService(optedIn = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.ranking!!.optedOut)
        assertEquals(EngagementPhase.Ready, model.phase)
        assertEquals("$1,240", model.snapshot?.impact?.givenLabel)
        assertNull(model.pendingOptIn)
    }

    @Test fun theToggleFlipsAtOnceAndOptingBackInRestoresTheStanding() = runTest {
        val model = EngagementModel(FakeEngagementService(optedIn = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false)
        assertTrue(model.displayedRanking!!.optedOut) // flipped before the save answers
        dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(true)
        assertFalse(model.displayedRanking!!.optedOut)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Top 2% nationally", model.displayedRanking!!.nationalLabel.textOrNull())
    }

    @Test fun aRefreshStartedBeforeTheToggleCannotOverwriteItsResult() = runTest {
        val service = FakeEngagementService(optedIn = true)
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        service.gate = null
        model.setRankingOptIn(false); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.ranking!!.optedOut)
        gate.complete(Unit); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.ranking!!.optedOut)
        assertEquals(EngagementPhase.Ready, model.phase)
    }

    @Test fun failedToggleRevertsAndShowsAnInlineError() = runTest {
        val model = EngagementModel(FakeEngagementService(failToggle = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false)
        assertTrue(model.displayedRanking!!.optedOut)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertFalse(model.displayedRanking!!.optedOut)
        assertNotNull(model.actionError)
    }

    @Test fun aCancelledToggleKeepsTheStandingWithoutAnError() = runTest {
        val service = FakeEngagementService().apply { cancelOnToggle = true }
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.displayedRanking!!.optedOut)
        assertNull(model.actionError)
        assertNull(model.pendingOptIn)
    }

    @Test fun choosingAPublicNameReloadsSoTheInviteAppears() = runTest {
        val service = FakeEngagementService(needsName = true)
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.needsPublicName)
        model.choosePublicName("  Alex C.  "); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.snapshot!!.needsPublicName)
        assertEquals("ALEX", model.snapshot!!.referral?.code)
        assertEquals(2, service.loads)
    }

    @Test fun blankOrOverlongNamesAreRefusedLocally() = runTest {
        val service = FakeEngagementService(needsName = true)
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.choosePublicName("   ")
        assertNotNull(model.nameError)
        model.choosePublicName("x".repeat(81))
        assertNotNull(model.nameError)
        assertEquals(1, service.loads)
    }

    @Test fun rejectedNamesExplainThemselvesAndKeepTheScreen() = runTest {
        val service = FakeEngagementService(needsName = true, failName = AccountFailure.InvalidInput)
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.choosePublicName("Alex"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("That name can’t be used. Try another.", model.nameError.textOrNull())
        assertTrue(model.snapshot!!.needsPublicName)
        assertFalse(model.isSavingName)
        service.failName = AccountFailure.Forbidden
        model.choosePublicName("Alex"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Invite links aren’t available for this account.", model.nameError.textOrNull())
    }

    @Test fun spendModelReadsPublishedUnpublishedAndFailed() = runTest {
        var failure: Throwable? = null
        val service = object : EngagementService {
            override suspend fun snapshot() = throw UnsupportedOperationException()
            override suspend fun setRankingOptIn(optIn: Boolean) = throw UnsupportedOperationException()
            override suspend fun choosePublicName(name: String) = throw UnsupportedOperationException()
            override suspend fun initiativeSpend(id: String): InitiativeSpend {
                failure?.let { throw it }
                return InitiativeSpend(500_000, listOf(SpendCategory("Food", 500_000)))
            }
        }
        val ready = InitiativeSpendModel(service, "id"); ready.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("$5,000", (ready.phase as InitiativeSpendModel.Phase.Ready).spend.totalLabel)
        failure = AccountFailure.NotFound
        val none = InitiativeSpendModel(service, "id"); none.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(InitiativeSpendModel.Phase.Unpublished, none.phase)
        failure = AccountFailure.Unavailable
        val failed = InitiativeSpendModel(service, "id"); failed.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(failed.phase is InitiativeSpendModel.Phase.Failed)
        failure = AccountFailure.SignedOut
        val out = InitiativeSpendModel(service, "id"); out.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(out.signedOut)
    }
}
