package org.dad.foundation

import android.app.Activity
import android.os.Bundle
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCredentialsTest {
    private fun activity(): Activity {
        lateinit var activity: Activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity = Activity() }
        return activity
    }

    private class Platform(var get: suspend (Activity, GetCredentialRequest) -> Credential) : CredentialPlatform {
        var cleared = false
        override suspend fun get(activity: Activity, request: GetCredentialRequest) = get.invoke(activity, request)
        override suspend fun clear() { cleared = true }
    }

    @Test fun googleRequestBindsNonceAndUsesTheReplacementActivity() = runBlocking {
        val host = CredentialActivityHost()
        val old = activity()
        val replacement = activity()
        host.bind(old)
        host.bind(replacement)
        host.unbind(old)
        val token = """{"alg":"RS256","typ":"JWT"}""".toByteArray().base64Url() + "." +
            """{"sub":"synthetic-user","email":"alex@example.org","nonce":"fresh-nonce"}""".toByteArray().base64Url() + ".c2lnbmF0dXJl"
        val platform = Platform { activity, request ->
            assertSame(replacement, activity)
            val option = request.credentialOptions.single() as GetSignInWithGoogleOption
            assertEquals("web.apps.googleusercontent.com", option.serverClientId)
            assertEquals("fresh-nonce", option.nonce)
            GoogleIdTokenCredential.Builder().setId("alex@example.org").setIdToken(token).build()
        }
        val provider = PlatformGoogleTokens(host, "web.apps.googleusercontent.com", platform)
        assertEquals(token, provider.idToken("fresh-nonce"))
        provider.clear()
        assertTrue(platform.cleared)
        host.unbind(replacement)
        assertEquals(ExternalSignInError.Cancelled, runCatching { provider.idToken("next") }.exceptionOrNull())
    }

    @Test fun cancelledAndMalformedPlatformResponsesNeverBecomeTokens() = runBlocking {
        val current = activity()
        val host = CredentialActivityHost().also { it.bind(current) }
        val platform = Platform { _, _ -> throw GetCredentialCancellationException() }
        val provider = PlatformGoogleTokens(host, "web.apps.googleusercontent.com", platform)
        assertEquals(ExternalSignInError.Cancelled, runCatching { provider.idToken("n") }.exceptionOrNull())
        platform.get = { _, _ -> CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle()) }
        assertEquals(AccountFailure.InvalidResponse, runCatching { provider.idToken("n") }.exceptionOrNull())
        platform.get = { _, _ -> CustomCredential("unexpected", Bundle()) }
        assertEquals(AccountFailure.InvalidResponse, runCatching { provider.idToken("n") }.exceptionOrNull())
    }
}
