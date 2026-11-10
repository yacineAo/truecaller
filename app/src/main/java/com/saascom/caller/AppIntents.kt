package com.saascom.caller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Where a tap on the after-call bar or a notification takes the user inside the app. */
sealed interface OpenTarget {
    data class AfterCall(val callId: Long?, val phone: String) : OpenTarget
    data class NewLead(val phone: String, val callId: Long?) : OpenTarget
    data class Lead(val phone: String) : OpenTarget
}

/**
 * Explicit intents to [MainActivity] (no exported deep link: other apps cannot open these screens).
 */
object AppIntents {
    private const val EXTRA_OPEN = "open"
    private const val EXTRA_PHONE = "phone"
    private const val EXTRA_CALL_ID = "call_id"

    fun open(context: Context, target: OpenTarget): Intent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when (target) {
            is OpenTarget.AfterCall -> intent.putExtra(EXTRA_OPEN, "after_call").putExtra(EXTRA_PHONE, target.phone)
                .apply { target.callId?.let { putExtra(EXTRA_CALL_ID, it) } }
            is OpenTarget.NewLead -> intent.putExtra(EXTRA_OPEN, "new_lead").putExtra(EXTRA_PHONE, target.phone)
                .apply { target.callId?.let { putExtra(EXTRA_CALL_ID, it) } }
            is OpenTarget.Lead -> intent.putExtra(EXTRA_OPEN, "lead").putExtra(EXTRA_PHONE, target.phone)
        }
        return intent
    }

    fun pending(context: Context, requestCode: Int, target: OpenTarget): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            open(context, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Opens the dialer with the number filled in (no call permission needed). */
    fun dial(context: Context, requestCode: Int, phone: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            dialIntent(phone),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun dialIntent(phone: String) = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun parse(intent: Intent?): OpenTarget? {
        val phone = intent?.getStringExtra(EXTRA_PHONE) ?: return null
        val callId = if (intent.hasExtra(EXTRA_CALL_ID)) intent.getLongExtra(EXTRA_CALL_ID, 0) else null
        return when (intent.getStringExtra(EXTRA_OPEN)) {
            "after_call" -> OpenTarget.AfterCall(callId, phone)
            "new_lead" -> OpenTarget.NewLead(phone, callId)
            "lead" -> OpenTarget.Lead(phone)
            else -> null
        }
    }
}
