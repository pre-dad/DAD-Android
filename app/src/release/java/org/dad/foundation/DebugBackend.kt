package org.dad.foundation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

/** Release builds contain no scripted backend; the composition root treats null as "not available". */
object DebugBackend {
    const val PUBLIC_ORIGIN = ""
    @Suppress("UNUSED_PARAMETER")
    fun stub(context: Context, scope: CoroutineScope, options: LaunchOptions): AppServices? = null
}

/** Release builds always use the real clipboard/share-sheet sharer; there is no capturing double. */
@Suppress("UNUSED_PARAMETER")
fun debugReferralSharer(context: Context, publicOrigin: String): ReferralSharer = AndroidReferralSharer(context)

/** Release builds have no preview doubles; a blank API origin is a configuration error. */
@Suppress("UNUSED_PARAMETER")
fun debugPreviewServices(scope: CoroutineScope, options: LaunchOptions): AppServices? = null

/** Release builds ignore every `preview_*` extra. */
@Suppress("UNUSED_PARAMETER")
fun debugPreviewScreen(context: Context, intent: Intent): (@Composable () -> Unit)? = null
