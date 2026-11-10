package com.saascom.caller.call

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import com.saascom.caller.AppIntents
import com.saascom.caller.OpenTarget
import com.saascom.caller.R
import com.saascom.caller.util.Formats

/** Notifications: card fallback while ringing, and the after-call reminders. */
object CallNotifier {
    const val CHANNEL_INCOMING = "incoming_card"
    const val CHANNEL_AFTER_CALL = "after_call"
    private const val INCOMING_ID = 1001

    /**
     * Fallback when the card cannot be drawn over the call screen ("display over other apps"
     * not granted): a heads-up notification with the name and status.
     */
    fun show(context: Context, state: CardState) {
        if (!allowed(context)) return
        val summary = state.summary()
        val notification = Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(summary.title)
            .setContentText(summary.text)
            .setCategory(Notification.CATEGORY_CALL)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager(context).notify(INCOMING_ID, notification)
    }

    fun cancel(context: Context) {
        manager(context).cancel(INCOMING_ID)
    }

    /** «سجّل المكالمة مع …» or «إضافة كعميل جديد؟»: stays until the call is logged or swiped away. */
    fun afterCall(context: Context, callId: Long, title: String, text: String, target: OpenTarget) {
        if (!allowed(context)) return
        val notification = Notification.Builder(context, CHANNEL_AFTER_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(AppIntents.pending(context, requestCode(callId), target))
            .setAutoCancel(true)
            .build()
        manager(context).notify(idFor(callId), notification)
    }

    /** «اتصل بك …» with «إعادة الاتصال» (dialer) and «فتح البطاقة». */
    fun missed(context: Context, callId: Long, phone: String, name: String?) {
        if (!allowed(context)) return
        val who = name?.takeIf { it.isNotBlank() } ?: Formats.phone(phone)
        val icon = Icon.createWithResource(context, R.drawable.ic_launcher_foreground)
        val notification = Notification.Builder(context, CHANNEL_AFTER_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_missed_title, who))
            .setContentText(context.getString(R.string.notif_missed_text))
            .setContentIntent(AppIntents.pending(context, requestCode(callId), OpenTarget.Lead(phone)))
            .addAction(
                Notification.Action.Builder(icon, context.getString(R.string.notif_call_back), AppIntents.dial(context, requestCode(callId) + 1, phone)).build(),
            )
            .addAction(
                Notification.Action.Builder(icon, context.getString(R.string.notif_open_card), AppIntents.pending(context, requestCode(callId) + 2, OpenTarget.Lead(phone))).build(),
            )
            .setAutoCancel(true)
            .build()
        manager(context).notify(idFor(callId), notification)
    }

    fun cancelForCall(context: Context, callId: Long) {
        manager(context).cancel(idFor(callId))
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun idFor(callId: Long) = 2000 + (callId % 100_000).toInt()

    private fun requestCode(callId: Long) = (callId % 100_000).toInt() * 4
}
