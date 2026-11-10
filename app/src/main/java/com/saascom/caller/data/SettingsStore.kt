package com.saascom.caller.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings and sync bookkeeping (SharedPreferences: small, synchronous, safe from the screening service). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _lookupUnknownNumbers = MutableStateFlow(prefs.getBoolean(KEY_LOOKUP_UNKNOWN, true))

    /**
     * Ask the CRM about numbers that are not in the local list (a lead that arrived minutes ago).
     * On by default (company phones). Off keeps private calls on the phone.
     */
    val lookupUnknownNumbers: StateFlow<Boolean> = _lookupUnknownNumbers.asStateFlow()

    fun setLookupUnknownNumbers(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOOKUP_UNKNOWN, enabled).apply()
        _lookupUnknownNumbers.value = enabled
    }

    var directoryEtag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) = prefs.edit().putString(KEY_ETAG, value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** The brand autostart screen cannot be checked by code, so the user confirms it. */
    var autostartConfirmed: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    /** Last time the CRM was asked for a newer APK (at most once a day). */
    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_UPDATE_CHECK, 0)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECK, value).apply()

    /**
     * `min_version_code` the CRM last announced. While this build is below it the update is
     * required, so the once-a-day pause of the check no longer applies.
     */
    var lastKnownMinVersion: Int
        get() = prefs.getInt(KEY_MIN_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_MIN_VERSION, value).apply()

    /** Forget sync state on logout so the next login downloads the whole list again. */
    fun clearSync() {
        prefs.edit().remove(KEY_ETAG).remove(KEY_LAST_SYNC).apply()
    }

    private companion object {
        const val KEY_LOOKUP_UNKNOWN = "lookup_unknown_numbers"
        const val KEY_ETAG = "directory_etag"
        const val KEY_LAST_SYNC = "directory_last_sync"
        const val KEY_AUTOSTART = "autostart_confirmed"
        const val KEY_UPDATE_CHECK = "update_last_check"
        const val KEY_MIN_VERSION = "update_min_version"
    }
}
