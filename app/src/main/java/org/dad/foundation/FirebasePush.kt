package org.dad.foundation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The FCM token, or null while the build carries no `google-services.json` and Firebase is therefore not initialised. */
fun firebasePushTokens(context: Context): PushTokenProvider {
    val app = context.applicationContext
    return GuardedPushTokenProvider(
        available = { firebasePushAvailable(app) },
        fetch = {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token unavailable"))
                }
            }
        },
    )
}

fun firebasePushAvailable(context: Context): Boolean = FirebaseApp.getApps(context.applicationContext).isNotEmpty()

/** Remembers that the notification permission was already requested, so a refusal is not asked again. */
class PushPermissionLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("org.dad.push", Context.MODE_PRIVATE)
    var asked: Boolean
        get() = prefs.getBoolean(ASKED, false)
        set(value) { prefs.edit { putBoolean(ASKED, value) } }
    private companion object { const val ASKED = "permission_asked" }
}

/** Shows push notifications, one channel per category so the system settings mirror the app's opt-outs. */
class PushNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(notice: PushNotice) {
        // Without the permission (or with notifications switched off) the system would drop the post anyway.
        if (!manager.areNotificationsEnabled()) return
        val channelId = ensureChannel(notice.category)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("category", notice.tap?.category)
            .putExtra("path", notice.tap?.path)
        val id = "${notice.tap?.category}|${notice.tap?.path}".hashCode()
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val built = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(id, built)
    }

    private fun ensureChannel(category: PushCategory?): String {
        val id = category?.channelId ?: GENERAL
        val name = category?.label ?: "General"
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT))
        return id
    }

    private companion object { const val GENERAL = "push_general" }
}

class DadMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushTokenRelay.rotated(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notice = PushNotices.from(message.data, PrefsPushOptOuts(this)) ?: return
        PushNotifier(this).show(notice)
    }
}
