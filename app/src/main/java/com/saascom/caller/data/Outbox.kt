package com.saascom.caller.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.saascom.caller.R
import com.saascom.caller.Services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What [Outbox.decide] does with one queued write after trying to send it. */
enum class SendOutcome {
    /** Accepted by the CRM: remove it. */
    Sent,

    /** No answer or server error: keep it and try again later; stop for now to keep the order. */
    RetryLater,

    /** Refused by the CRM (4xx): keep it, flagged, for the user to look at; go on with the next one. */
    Refused,

    /** 401: the user was logged out; the local data is being wiped anyway. */
    Stop,
}

/**
 * Writes that could not reach the CRM (no internet) wait here and are replayed in order.
 * Calls, call history and the outbox are also exposed as flows for the screens.
 */
class Outbox(private val context: Context, private val db: LocalDb, private val api: ApiClient) {
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<PendingAction>>(emptyList())
    private val _calls = MutableStateFlow<List<CallRecord>>(emptyList())

    val items: StateFlow<List<PendingAction>> = _items.asStateFlow()
    val calls: StateFlow<List<CallRecord>> = _calls.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _items.value = db.outbox()
        _calls.value = db.recentCalls()
    }

    /** Queues a write that failed for lack of network, and schedules the sender. */
    suspend fun enqueue(kind: String, path: String, body: String, label: String, callId: Long?) {
        withContext(Dispatchers.IO) {
            db.enqueue(kind, path, body, label, callId, System.currentTimeMillis())
            callId?.let(db::markLogged) // the call counts as logged: it is on its way
        }
        refresh()
        schedule(context)
    }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { db.deleteAction(id) }
        refresh()
    }

    /** Sends everything that is waiting. Returns false when some writes must be retried later. */
    suspend fun flush(): Boolean = mutex.withLock {
        var done = true
        var leadCreated = false
        val leadCalls = mutableListOf<Long>()
        for (action in withContext(Dispatchers.IO) { db.outbox() }) {
            if (action.lastError != null) continue // refused before: waits for the user
            val (outcome, message) = try {
                api.postRaw(action.path, action.body)
                SendOutcome.Sent to null
            } catch (e: ApiException) {
                decide(e) to refusalText(action.kind, e)
            }
            withContext(Dispatchers.IO) {
                when (outcome) {
                    SendOutcome.Sent -> db.deleteAction(action.id)
                    SendOutcome.Refused -> db.failAction(action.id, message ?: "", action.attempts + 1)
                    SendOutcome.RetryLater -> db.countAttempt(action.id, action.attempts + 1)
                    SendOutcome.Stop -> Unit
                }
                loggedAfter(outcome)?.let { logged ->
                    action.callId?.let { if (logged) db.markLogged(it) else db.markUnlogged(it) }
                }
            }
            if (outcome == SendOutcome.Sent && action.kind == KIND_LEAD) {
                leadCreated = true
                action.callId?.let(leadCalls::add)
            }
            if (outcome == SendOutcome.RetryLater || outcome == SendOutcome.Stop) {
                done = false
                break
            }
        }
        // A lead created from the queue must be in the local list for its next call,
        // and the history rows of the calls it came from must point to it.
        if (leadCreated) {
            syncDirectory()
            linkCallsToLeads(leadCalls)
        }
        refresh()
        done
    }

    /** The CRM now has the lead behind each call that came from an unknown number: find it by phone. */
    private suspend fun linkCallsToLeads(callIds: List<Long>) = withContext(Dispatchers.IO) {
        for (id in callIds) {
            val call = db.call(id) ?: continue
            val entry = Services.directory.find(call.phone) ?: continue
            db.updateCallLead(id, entry.leadId, entry.name ?: entry.company)
        }
    }

    private suspend fun syncDirectory() {
        try {
            Services.directory.sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The periodic sync will catch up.
        }
    }

    /** The CRM's reason for a refusal, in Arabic: a policy 403 means the lead is not the agent's. */
    private fun refusalText(kind: String, error: ApiException): String {
        val generic = context.getString(R.string.error_generic)
        return when (error) {
            is ApiException.Http -> {
                val forbidden = if (kind == KIND_LEAD) generic else context.getString(R.string.error_not_your_lead)
                error.userMessage(forbidden, generic)
            }
            else -> error.message ?: generic
        }
    }

    companion object {
        const val KIND_CALL = "call"
        const val KIND_LEAD = "lead"
        const val KIND_REQUEST = "request"

        private const val WORK = "outbox-send"

        /** Retry rules (phases/phase-2-after-call.md 2.6). */
        fun decide(error: ApiException): SendOutcome = when {
            error is ApiException.Network -> SendOutcome.RetryLater
            error is ApiException.Http && error.code == 401 -> SendOutcome.Stop
            error is ApiException.Http && error.code == 429 -> SendOutcome.RetryLater
            error is ApiException.Http && error.code in 400..499 -> SendOutcome.Refused
            else -> SendOutcome.RetryLater // 5xx: the server may be restarting
        }

        /**
         * What a send result means for the «logged» flag of the call behind the write: a call is
         * marked logged when it is queued, so a refusal must undo that. null: leave it as it is.
         */
        fun loggedAfter(outcome: SendOutcome): Boolean? = when (outcome) {
            SendOutcome.Sent -> true
            SendOutcome.Refused -> false
            SendOutcome.RetryLater, SendOutcome.Stop -> null
        }

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SendOutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}

/** Sends the outbox when the network is back; WorkManager retries with backoff. */
class SendOutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Services.session.isLoggedIn) return Result.success()
        return if (Services.outbox.flush()) Result.success() else Result.retry()
    }
}
