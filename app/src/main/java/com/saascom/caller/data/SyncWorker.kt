package com.saascom.caller.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.saascom.caller.Services
import java.util.concurrent.TimeUnit

/** Refreshes the client list in the background (every 2 h, and right after login). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Services.session.isLoggedIn) return Result.success()
        return try {
            Services.directory.sync()
            // Housekeeping: the call history keeps 30 days; unsent writes get another chance.
            Services.localDb.deleteCallsBefore(System.currentTimeMillis() - HISTORY_DAYS * 24 * 3600 * 1000L)
            Services.outbox.flush()
            Result.success()
        } catch (e: ApiException.Network) {
            Result.retry()
        } catch (e: ApiException) {
            Result.failure() // 401 already logged the user out; other errors wait for the next run
        }
    }

    companion object {
        private const val HISTORY_DAYS = 30
        private const val PERIODIC = "directory-sync"
        private const val NOW = "directory-sync-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(2, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(NOW)
        }
    }
}
