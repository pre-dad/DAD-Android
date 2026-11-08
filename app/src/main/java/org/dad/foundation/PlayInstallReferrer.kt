package org.dad.foundation

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One connection to the Play Store's install referrer service; a seam so the response handling is unit-tested. */
interface ReferrerConnection {
    /** Starts connecting; [onFinished] gets an `InstallReferrerClient.InstallReferrerResponse` code. */
    fun start(onFinished: (Int) -> Unit)
    /** The raw referrer string; blocking, so call it off the main thread. */
    fun read(): String?
    fun end()
}

class PlayReferrerConnection(context: Context) : ReferrerConnection {
    private val client = InstallReferrerClient.newBuilder(context.applicationContext).build()

    override fun start(onFinished: (Int) -> Unit) = client.startConnection(object : InstallReferrerStateListener {
        override fun onInstallReferrerSetupFinished(responseCode: Int) = onFinished(responseCode)
        override fun onInstallReferrerServiceDisconnected() {}
    })

    override fun read(): String? = client.installReferrer.installReferrer
    override fun end() = client.endConnection()
}

/**
 * The real [InstallReferrer] over the Play Install Referrer library. Everything short of a referrer string is "none":
 * Play unavailable or unsupported, a refused or dropped connection, a read error, or no answer within [timeoutMs].
 * The connection starts on the caller's thread but the read runs on [io], and the connection is always closed.
 */
class PlayInstallReferrer(
    private val connect: () -> ReferrerConnection,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = InstallReferrerIntake.READ_TIMEOUT_MS,
) : InstallReferrer {
    override suspend fun fetch(): String? {
        val connection = try { connect() } catch (_: Exception) { return null }
        try {
            val response = withTimeoutOrNull(timeoutMs) { await(connection) }
            if (response != InstallReferrerClient.InstallReferrerResponse.OK) return null
            return withContext(io) { try { connection.read() } catch (_: Exception) { null } }
        } finally {
            try { connection.end() } catch (_: Exception) {}
        }
    }

    private suspend fun await(connection: ReferrerConnection): Int = suspendCancellableCoroutine { continuation ->
        try {
            connection.start { code -> if (continuation.isActive) continuation.resume(code) }
        } catch (_: Exception) {
            continuation.resume(InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE)
        }
    }
}
