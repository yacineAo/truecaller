package com.saascom.caller

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.saascom.caller.call.CallNotifier
import com.saascom.caller.data.Outbox
import com.saascom.caller.data.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    /** Lives as long as the process: for work that must not stop with a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Services.init(this)

        // Decrypt the token now, so the screening service has it instantly when a call arrives.
        if (Services.session.isLoggedIn) SyncWorker.schedule(this)
        scope.launch {
            Services.directory.refreshCount()
            Services.outbox.refresh()
            if (Services.session.isLoggedIn && Services.outbox.items.value.any { it.lastError == null }) Outbox.schedule(this@App)
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CallNotifier.CHANNEL_INCOMING,
                getString(R.string.channel_incoming),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.channel_incoming_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CallNotifier.CHANNEL_AFTER_CALL,
                getString(R.string.channel_after_call),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.channel_after_call_desc) },
        )
    }
}
