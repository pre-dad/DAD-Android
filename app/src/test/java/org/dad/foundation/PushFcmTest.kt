package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushFcmTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Api : AccountAPI {
        override suspend fun profile(access: String) = AccountProfile("a@b.c", "Alex")
        override suspend fun refresh(token: String): Credentials = throw AccountFailure.SignedOut
        override suspend fun claim(token: String) = validCredentials("claimed")
        override suspend fun logout(access: String) = Unit
    }

    private class RecordingPush(var failure: Exception? = null) : PushRegistration {
        val registered = mutableListOf<String>()
        override suspend fun register(token: String) { failure?.let { throw it }; registered += token }
        override suspend fun unregister() = Unit
    }

    private fun model(push: RecordingPush, tokens: PushTokenProvider?, store: RecordingStore = RecordingStore(validCredentials())) =
        AccountModel({ scope, _ ->
            AppServices(AccountSession(Api(), store, scope, dispatcher), UnconfiguredSignIn, null, push = push, pushTokens = tokens)
        }, dispatcher)

    private fun optOuts(vararg off: PushCategory) = object : PushOptOuts { override fun allows(category: PushCategory) = category !in off }

    // --- provider ---
    @Test fun anUnavailableProviderAnswersNullAndNeverFetches() = runTest {
        val provider = GuardedPushTokenProvider(available = { false }, fetch = { error("must not be called") })
        assertNull(provider.token())
    }

    @Test fun aFailingFetchAnswersNullInsteadOfThrowing() = runTest {
        assertNull(GuardedPushTokenProvider(available = { true }, fetch = { throw IllegalStateException("play services") }).token())
        assertNull(GuardedPushTokenProvider(available = { true }, fetch = { "  " }).token())
    }

    @Test fun cancellationIsNotSwallowed() = runTest {
        val provider = GuardedPushTokenProvider(available = { true }, fetch = { throw CancellationException("stop") })
        val outcome = runCatching { provider.token() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }

    @Test fun anAvailableProviderReturnsItsToken() = runTest {
        assertEquals("fcm-1", GuardedPushTokenProvider(available = { true }, fetch = { "fcm-1" }).token())
    }

    // --- registration and refresh ---
    @Test fun aSignedInLaunchRegistersTheProvidersToken() = runTest {
        val push = RecordingPush()
        model(push, PushTokenProvider { "fcm-1" }).start()
        assertEquals(listOf("fcm-1"), push.registered)
    }

    @Test fun anUnavailableProviderRegistersNothing() = runTest {
        val push = RecordingPush()
        model(push, GuardedPushTokenProvider(available = { false }, fetch = { "never" })).start()
        assertTrue(push.registered.isEmpty())
    }

    @Test fun aRotatedTokenIsRegisteredWhileSignedIn() = runTest {
        val push = RecordingPush()
        model(push, PushTokenProvider { "fcm-1" }).start()
        PushTokenRelay.rotated("fcm-2")
        assertEquals(listOf("fcm-1", "fcm-2"), push.registered)
    }

    @Test fun aRotatedTokenIsNotRegisteredWithoutASession() = runTest {
        val push = RecordingPush()
        model(push, PushTokenProvider { "fcm-1" }, store = RecordingStore()).start()
        PushTokenRelay.rotated("fcm-2")
        assertTrue(push.registered.isEmpty())
    }

    @Test fun aFailedRotationRegistrationDoesNotCrashTheModel() = runTest {
        val push = RecordingPush()
        val m = model(push, PushTokenProvider { "fcm-1" })
        m.start()
        push.failure = AccountFailure.Unavailable
        PushTokenRelay.rotated("fcm-2")
        assertEquals(listOf("fcm-1"), push.registered)
        assertTrue(m.hasSession)
    }

    // --- routing and opt-out ---
    private fun data(category: String?, path: String? = "/account/gifts", body: String? = "Your gift was processed.", title: String? = null) =
        listOfNotNull(category?.let { "category" to it }, path?.let { "path" to it }, body?.let { "body" to it }, title?.let { "title" to it }).toMap()

    @Test fun extrasRouteToTheCategoryAndPath() {
        val notice = PushNotices.from(data("GIFT_PROCESSED"), optOuts())!!
        assertEquals(PushCategory.GIFT_PROCESSED, notice.category)
        assertEquals(PushTap("GIFT_PROCESSED", "/account/gifts"), notice.tap)
        assertEquals("Gift processed", notice.title)
    }

    @Test fun aServerTitleWinsOverTheCategoryLabel() {
        assertEquals("Keep it going", PushNotices.from(data("STREAK_AT_RISK", title = "Keep it going"), optOuts())!!.title)
    }

    @Test fun anOptedOutCategoryIsDropped() {
        assertNull(PushNotices.from(data("STREAK_AT_RISK"), optOuts(PushCategory.STREAK_AT_RISK)))
        assertTrue(PushNotices.from(data("STREAK_MILESTONE"), optOuts(PushCategory.STREAK_AT_RISK)) != null)
    }

    @Test fun anUnknownCategoryStillShowsOnTheGeneralChannel() {
        val notice = PushNotices.from(data("SOMETHING_NEW"), optOuts())!!
        assertNull(notice.category)
        assertEquals("DAD", notice.title)
    }

    @Test fun malformedOrEmptyMessagesShowNothing() {
        assertNull(PushNotices.from(data(null), optOuts()))
        assertNull(PushNotices.from(data("bad category"), optOuts()))
        assertNull(PushNotices.from(data("GIFT_PROCESSED", body = null), optOuts()))
        assertNull(PushNotices.from(data("GIFT_PROCESSED", body = "   "), optOuts()))
    }

    @Test fun everySettingsPushToggleHasACategoryAndChannel() {
        val prefs = Preferences(
            version = 1, referralEmails = true, marketingEmails = false,
            pushReferralConverted = true, pushStreakAtRisk = true, pushStreakMilestone = true,
            pushStreakLapsed = true, pushBadgeEarned = true, pushEventNearby = true,
            pushEventReminder = true, pushEventPromoted = true, pushGiftProcessed = true,
            rankingsOptedOut = false, networkVisible = true, volunteer = true,
        )
        assertEquals(prefs.pushToggles.map { it.key }.toSet(), PushCategory.entries.map { it.settingsKey }.toSet())
        assertEquals(PushCategory.entries.size, PushCategory.entries.map { it.channelId }.toSet().size)
    }

    // --- permission ---
    @Test fun thePermissionIsAskedOnceAfterSignInWhereItIsNeeded() {
        assertTrue(PushPermissionPolicy.shouldAsk(33, granted = false, alreadyAsked = false, pushAvailable = true, signedIn = true))
        assertFalse(PushPermissionPolicy.shouldAsk(32, granted = false, alreadyAsked = false, pushAvailable = true, signedIn = true))
        assertFalse(PushPermissionPolicy.shouldAsk(34, granted = true, alreadyAsked = false, pushAvailable = true, signedIn = true))
        assertFalse(PushPermissionPolicy.shouldAsk(34, granted = false, alreadyAsked = true, pushAvailable = true, signedIn = true))
        assertFalse(PushPermissionPolicy.shouldAsk(34, granted = false, alreadyAsked = false, pushAvailable = false, signedIn = true))
        assertFalse(PushPermissionPolicy.shouldAsk(34, granted = false, alreadyAsked = false, pushAvailable = true, signedIn = false))
    }

    // --- settings mirroring ---
    @Test fun settingsMirrorThePushTogglesAndSignOutForgetsThem() = runTest {
        val recorded = mutableListOf<Preferences>()
        var cleared = 0
        val service = PushMirroringSettings(PreviewSettingsService(), recorded::add) { cleared++ }
        service.load()
        val saved = service.savePreferences(service.load().preferences.with("push_gift_processed", false))
        assertFalse(saved.preferences.pushGiftProcessed)
        assertFalse(recorded.last().pushGiftProcessed)
        service.signOut()
        assertEquals(1, cleared)
    }
}
