package com.saascom.caller

import android.content.Context
import com.saascom.caller.call.CallerCardController
import com.saascom.caller.data.ApiClient
import com.saascom.caller.data.DirectoryDb
import com.saascom.caller.data.DirectoryRepository
import com.saascom.caller.data.LocalDb
import com.saascom.caller.data.Outbox
import com.saascom.caller.data.Session
import com.saascom.caller.data.SettingsStore
import com.saascom.caller.data.SyncWorker

/**
 * The few app-wide objects, shared by the screens, the screening service and the worker.
 * Plain manual wiring: the app is small and every entry point needs the same instances.
 */
object Services {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val session: Session by lazy { Session(appContext) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val api: ApiClient by lazy {
        ApiClient(
            baseUrl = { session.baseUrl },
            token = { session.token },
            appVersion = BuildConfig.VERSION_CODE,
            onUnauthorized = ::logoutLocally,
        )
    }

    val directory: DirectoryRepository by lazy { DirectoryRepository(api, DirectoryDb(appContext), settings) }

    /** Call history and unsent writes (not a cache: survives list refreshes). */
    val localDb: LocalDb by lazy { LocalDb(appContext) }

    val outbox: Outbox by lazy { Outbox(appContext, localDb, api) }

    /**
     * Forget the user on this phone: token, client list, background sync, any visible card.
     * Used by "logout" and when the CRM answers 401 (disabled user, phone disconnected by an admin).
     */
    fun logoutLocally() {
        session.clear()
        directory.clear()
        localDb.clear()
        SyncWorker.cancel(appContext)
        Outbox.cancel(appContext)
        CallerCardController.dismiss(appContext)
    }
}
