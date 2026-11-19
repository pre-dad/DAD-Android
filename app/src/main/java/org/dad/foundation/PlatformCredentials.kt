package org.dad.foundation

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.lang.ref.WeakReference

/** The retained auth services must not capture the Activity that created them. */
class CredentialActivityHost {
    private var current = WeakReference<Activity>(null)
    fun bind(activity: Activity) { current = WeakReference(activity) }
    fun unbind(activity: Activity) { if (current.get() === activity) current.clear() }
    fun activity(): Activity = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        ?: throw ExternalSignInError.Cancelled
}

internal interface CredentialPlatform {
    suspend fun get(activity: Activity, request: GetCredentialRequest): Credential
    suspend fun clear()
}

private class CredentialManagerPlatform(context: Context) : CredentialPlatform {
    private val manager = CredentialManager.create(context.applicationContext)
    override suspend fun get(activity: Activity, request: GetCredentialRequest): Credential = manager.getCredential(activity, request).credential
    override suspend fun clear() { manager.clearCredentialState(ClearCredentialStateRequest()) }
}

internal class PlatformGoogleTokens(
    private val host: CredentialActivityHost, private val clientId: String, private val platform: CredentialPlatform,
) : GoogleIdTokenProvider {
    constructor(context: Context, host: CredentialActivityHost, clientId: String) : this(host, clientId, CredentialManagerPlatform(context))

    override suspend fun idToken(nonce: String): String {
        val option = GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build()
        val credential = try {
            platform.get(host.activity(), GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (_: GetCredentialCancellationException) { throw ExternalSignInError.Cancelled }
        catch (_: GetCredentialProviderConfigurationException) { throw ExternalSignInError.Unsupported }
        catch (_: GetCredentialUnsupportedException) { throw ExternalSignInError.Unsupported }
        catch (_: NoCredentialException) { throw ExternalSignInError.Unsupported }
        catch (_: GetCredentialException) { throw AccountFailure.Unavailable }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AccountFailure.InvalidResponse
        }
        return try { GoogleIdTokenCredential.createFrom(credential.data).idToken }
        catch (_: GoogleIdTokenParsingException) { throw AccountFailure.InvalidResponse }
        catch (_: IllegalArgumentException) { throw AccountFailure.InvalidResponse }
    }

    suspend fun clear() = platform.clear()
}
