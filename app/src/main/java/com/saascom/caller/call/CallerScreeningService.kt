package com.saascom.caller.call

import android.telecom.Call
import android.telecom.CallScreeningService
import com.saascom.caller.util.PhoneNormalizer

/**
 * Android calls this for each new call once the app holds the "Caller ID & spam" role
 * (and, for numbers saved in contacts, only if the app may read contacts).
 */
class CallerScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        // Never block or delay a call: allow it right away, then look the number up.
        respondToCall(details, CallResponse.Builder().build())

        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val phone = PhoneNormalizer.normalize(details.handle?.schemeSpecificPart) ?: return // hidden number
        CallerCardController.onIncomingCall(applicationContext, phone)
    }
}
