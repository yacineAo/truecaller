package com.saascom.caller.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.saascom.caller.AppIntents
import com.saascom.caller.BuildConfig
import com.saascom.caller.OpenTarget
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.data.ApiException
import com.saascom.caller.data.LastCallDto
import com.saascom.caller.data.LeadCardDto
import com.saascom.caller.data.LeadStatusDto
import com.saascom.caller.data.PersonDto
import com.saascom.caller.data.RequestDto
import com.saascom.caller.data.SubscriptionDto
import com.saascom.caller.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The call the card is about, as known so far. */
data class CurrentCall(
    /** Row in the local call history. */
    val callId: Long,
    val phone: String,
    val leadId: Long?,
    val name: String?,
    /** The CRM (or the local list) knows the number. */
    val known: Boolean,
    /** The agent may log calls for this lead (full card). */
    val canLog: Boolean,
)

/**
 * Incoming call → local list (instant) → card → CRM details (≤ 3 s) → card updated →
 * call ends → after-call bar or notification (phases/phase-2-after-call.md 2.2).
 * Everything runs on the main thread; database and network work hop to IO.
 */
object CallerCardController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lookupJob: Job? = null
    private var safetyJob: Job? = null
    private var promptJob: Job? = null

    @Volatile
    var current: CurrentCall? = null
        private set

    /** Hide the card even if the end of the call was missed. */
    private const val SAFETY_HIDE_MS = 3 * 60 * 1000L

    /** The after-call bar turns into a notification after this. */
    private const val PROMPT_MS = 15_000L

    fun onIncomingCall(context: Context, phone: String) {
        val app = context.applicationContext
        if (!Services.session.isLoggedIn) return

        val startedAt = SystemClock.elapsedRealtime()
        lookupJob?.cancel()
        promptJob?.cancel() // a new call replaces the bar of the previous one
        lookupJob = scope.launch {
            val local = withContext(Dispatchers.IO) { Services.directory.find(phone) }?.toLocal()

            // Unknown numbers go to the CRM only if allowed (a personal phone keeps private calls private).
            if (local == null && !Services.settings.lookupUnknownNumbers.value) return@launch

            current = null
            CallStateWatcher.start(app) { onCallEnded(app) }
            scheduleSafetyHide(app)

            // A known client gets a card at once; an unknown number waits for the CRM answer.
            if (local != null) {
                remember(phone, local.leadId, displayName(local.name, local.company), known = true, canLog = true)
                render(app, CardState.Loading(phone, local))
                log("local card shown in ${SystemClock.elapsedRealtime() - startedAt} ms")
            }

            val state = try {
                CardState.fromLookup(phone, Services.api.lookup(phone))
            } catch (e: ApiException) {
                if (e is ApiException.Http && e.code == 401) return@launch // logged out meanwhile
                local?.let { CardState.Offline(phone, it) }
            }

            when (state) {
                is CardState.Full -> remember(phone, state.lead.id, displayName(state.lead.name, state.lead.company), known = true, canLog = true)
                is CardState.Limited -> remember(phone, state.lead.id, displayName(state.lead.name, state.lead.company), known = true, canLog = false)
                is CardState.Unknown -> remember(phone, null, null, known = false, canLog = false)
                else -> Unit
            }

            if (state != null) render(app, state) else dismiss(app)
            log("${state?.javaClass?.simpleName ?: "no"} card after ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    /** ✕ on the card: hides it, but the call is still followed (after-call bar, history). */
    fun closeCard(context: Context) {
        OverlayCard.hide(context.applicationContext)
        CallNotifier.cancel(context.applicationContext)
    }

    /** "Test card" button: a sample card, closed with ✕ or after 20 s. */
    fun showSample(context: Context) {
        val app = context.applicationContext
        render(app, sampleCard())
        safetyJob?.cancel()
        safetyJob = scope.launch {
            delay(20_000)
            dismiss(app)
        }
    }

    /** Full reset: no after-call step (logout, safety timer, failed lookup of an unknown number). */
    fun dismiss(context: Context) {
        val app = context.applicationContext
        lookupJob?.cancel()
        safetyJob?.cancel()
        promptJob?.cancel()
        CallStateWatcher.stop(app)
        OverlayCard.hide(app)
        CallNotifier.cancel(app)
        current = null
    }

    /** Creates or updates the call history row with what is known about the caller. */
    private suspend fun remember(phone: String, leadId: Long?, name: String?, known: Boolean, canLog: Boolean) {
        val previous = current
        val callId = withContext(Dispatchers.IO) {
            if (previous == null) {
                Services.localDb.insertCall(phone, leadId, name, System.currentTimeMillis())
            } else {
                if (previous.leadId != leadId || previous.name != name) Services.localDb.updateCallLead(previous.callId, leadId, name)
                previous.callId
            }
        }
        current = CurrentCall(callId, phone, leadId, name, known, canLog)
    }

    private fun onCallEnded(context: Context) {
        val call = current
        val answeredAt = CallStateWatcher.answeredAt
        lookupJob?.cancel()
        safetyJob?.cancel()
        CallStateWatcher.stop(context)
        CallNotifier.cancel(context)
        current = null
        if (call == null) {
            OverlayCard.hide(context)
            return
        }

        val duration = answeredAt?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
        scope.launch {
            withContext(Dispatchers.IO) { Services.localDb.finishCall(call.callId, answeredAt != null, duration) }
            Services.outbox.refresh()
        }

        val who = call.name ?: Formats.phone(call.phone)
        when (AfterCallPolicy.decide(call.known, call.canLog, answered = answeredAt != null, durationSeconds = duration)) {
            AfterCallAction.LogPrompt -> prompt(
                context,
                call,
                title = context.getString(R.string.prompt_log, who),
                primary = context.getString(R.string.prompt_log_action),
                secondary = context.getString(R.string.prompt_later),
                target = OpenTarget.AfterCall(call.callId, call.phone),
                notifyLater = true,
            )
            AfterCallAction.AddLeadPrompt -> prompt(
                context,
                call,
                title = context.getString(R.string.prompt_add_lead, Formats.phone(call.phone)),
                primary = context.getString(R.string.prompt_add_lead_action),
                secondary = context.getString(R.string.prompt_ignore),
                target = OpenTarget.NewLead(call.phone, call.callId),
                notifyLater = false,
            )
            AfterCallAction.MissedNotice -> {
                OverlayCard.hide(context)
                CallNotifier.missed(context, call.callId, call.phone, call.name)
            }
            AfterCallAction.Nothing -> OverlayCard.hide(context)
        }
    }

    /**
     * The after-call bar: [primary] opens the screen; [secondary] closes it (and, when
     * [notifyLater], leaves a notification). After 15 s it closes by itself and leaves the notification.
     */
    private fun prompt(
        context: Context,
        call: CurrentCall,
        title: String,
        primary: String,
        secondary: String,
        target: OpenTarget,
        notifyLater: Boolean,
    ) {
        val leaveNotification = {
            CallNotifier.afterCall(context, call.callId, title, context.getString(R.string.notif_after_call_text), target)
        }
        if (!OverlayCard.canShow(context)) {
            leaveNotification()
            return
        }
        promptJob?.cancel()
        OverlayCard.showPrompt(
            context,
            AfterCallPrompt(
                title = title,
                primary = primary,
                secondary = secondary,
                onPrimary = {
                    promptJob?.cancel()
                    OverlayCard.hide(context)
                    context.startActivity(AppIntents.open(context, target))
                },
                onSecondary = {
                    promptJob?.cancel()
                    OverlayCard.hide(context)
                    if (notifyLater) leaveNotification()
                },
            ),
        )
        promptJob = scope.launch {
            delay(PROMPT_MS)
            OverlayCard.hide(context)
            leaveNotification()
        }
    }

    private fun render(context: Context, state: CardState) {
        if (OverlayCard.canShow(context)) {
            OverlayCard.show(context, state, onClose = { closeCard(context) })
        } else {
            CallNotifier.show(context, state)
        }
    }

    /** Timing only (debug builds): never logs the number or client data. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d("CallerCard", message)
    }

    private fun scheduleSafetyHide(context: Context) {
        safetyJob?.cancel()
        safetyJob = scope.launch {
            delay(SAFETY_HIDE_MS)
            dismiss(context)
        }
    }

    private fun displayName(name: String?, company: String?): String? =
        name?.takeIf { it.isNotBlank() } ?: company?.takeIf { it.isNotBlank() }

    private fun sampleCard(): CardState {
        val now = Instant.now()
        return CardState.Full(
            phone = "0550123456",
            lead = LeadCardDto(
                id = 0,
                name = "محمد بن علي",
                company = "صيدلية النور",
                status = LeadStatusDto(label = "مهتم", stage = "interested"),
                assignedTo = PersonDto(0, Services.session.user.value?.name ?: "كريم"),
                wilaya = "وهران",
                lastCall = LastCallDto(
                    at = now.minus(3, ChronoUnit.DAYS).toString(),
                    result = "available",
                    by = "سارة",
                    note = "طلب عرض سعر لنسخة متعددة الأسعار",
                ),
                subscription = SubscriptionDto(type = "subscription", required = 30000.0, paid = 20000.0, remaining = 10000.0),
                openRequestsCount = 1,
                openRequests = listOf(RequestDto(id = 0, title = "مشكل في الطباعة", priority = "urgent")),
            ),
        )
    }
}
