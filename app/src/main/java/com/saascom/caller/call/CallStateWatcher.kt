package com.saascom.caller.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Tells when the call the card is about has ended.
 *
 * Android reports the current state as soon as we register, and the screening service runs
 * before the phone starts ringing, so an early IDLE does not mean "ended": we wait until we
 * have seen RINGING or OFFHOOK. If neither comes (caller hung up during screening), the card
 * is hidden after a short grace period.
 */
object CallStateWatcher {
    private const val NO_ACTIVITY_GRACE_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())
    private var registration: Any? = null
    private var onEnded: (() -> Unit)? = null
    private var seenActive = false

    /** Set when the call was picked up. Phase 2 reads it for the after-call screen. */
    @Volatile
    var answeredAt: Long? = null
        private set

    private val noActivity = Runnable { if (!seenActive) finish() }

    fun start(context: Context, onEnded: () -> Unit) {
        stop(context)
        this.onEnded = onEnded
        seenActive = false
        answeredAt = null
        handler.postDelayed(noActivity, NO_ACTIVITY_GRACE_MS)

        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return // no end-of-call signal: the card stays until ✕ or the controller's safety timer
        }
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        registration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerCallback(context, telephony)
        } else {
            registerListener(telephony)
        }
    }

    fun stop(context: Context) {
        handler.removeCallbacks(noActivity)
        onEnded = null
        val telephony = context.getSystemService(TelephonyManager::class.java)
        when (val r = registration) {
            null -> Unit
            is PhoneStateListener -> {
                @Suppress("DEPRECATION")
                telephony?.listen(r, PhoneStateListener.LISTEN_NONE)
            }
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) telephony?.unregisterTelephonyCallback(r as TelephonyCallback)
        }
        registration = null
    }

    private fun onState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> seenActive = true
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                seenActive = true
                if (answeredAt == null) answeredAt = System.currentTimeMillis()
            }
            TelephonyManager.CALL_STATE_IDLE -> if (seenActive) finish()
        }
    }

    private fun finish() {
        val callback = onEnded
        onEnded = null
        callback?.invoke()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCallback(context: Context, telephony: TelephonyManager): TelephonyCallback {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onState(state)
        }
        telephony.registerTelephonyCallback(context.mainExecutor, callback)
        return callback
    }

    @Suppress("DEPRECATION")
    private fun registerListener(telephony: TelephonyManager): PhoneStateListener {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
        }
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        return listener
    }
}
