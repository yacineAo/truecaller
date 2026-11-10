package com.saascom.caller.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.call.CallNotifier
import com.saascom.caller.data.ApiClient
import com.saascom.caller.data.ApiException
import com.saascom.caller.data.CallRecord
import com.saascom.caller.data.DirectoryEntry
import com.saascom.caller.data.LookupResponse
import com.saascom.caller.data.Outbox
import com.saascom.caller.data.ReminderChoice
import com.saascom.caller.data.StatusDto
import com.saascom.caller.data.logCallBody
import com.saascom.caller.ui.components.ChoiceChips
import com.saascom.caller.ui.components.ScreenColumn
import com.saascom.caller.ui.components.SectionLabel
import com.saascom.caller.util.Formats
import com.saascom.caller.util.Labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * «تسجيل المكالمة»: the CRM's log-call form (LeadActions::callHistory), simplified for a phone.
 * [callId] links it to a call from the history; null when logging by hand from the client page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfterCallScreen(
    callId: Long?,
    phone: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenRequest: (leadId: Long, name: String) -> Unit,
    onOpenNewLead: (phone: String, callId: Long?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var call by remember { mutableStateOf<CallRecord?>(null) }
    var local by remember { mutableStateOf<DirectoryEntry?>(null) }
    var lookup by remember { mutableStateOf<LookupResponse?>(null) }
    var offline by remember { mutableStateOf(false) }
    var statuses by remember { mutableStateOf<List<StatusDto>>(emptyList()) }

    LaunchedEffect(callId, phone) {
        call = callId?.let { withContext(Dispatchers.IO) { Services.localDb.call(it) } }
        statuses = Services.directory.statuses()
        local = withContext(Dispatchers.IO) { Services.directory.find(phone) }
        lookup = try {
            Services.api.lookup(phone)
        } catch (e: ApiException) {
            offline = true
            null
        }
        loading = false
    }

    // Form state
    var result by remember { mutableStateOf("available") }
    var notes by remember { mutableStateOf("") }
    var autoNote by remember { mutableStateOf("") }
    var newStatusId by remember { mutableStateOf<Long?>(null) }
    var reminder by remember { mutableStateOf<ReminderChoice>(ReminderChoice.Keep) }
    var suggestion by remember { mutableStateOf<FollowUpPrefill?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<Long?>(null) }
    var statusMenu by remember { mutableStateOf(false) }

    val lead = lookup?.lead
    val leadId = lead?.id ?: local?.leadId
    val name = lead?.name ?: lead?.company ?: local?.name ?: local?.company ?: Formats.phone(phone)
    val found = lookup?.found ?: (local != null)
    val fullAccess = lookup?.fullAccess ?: (local != null) // offline: the CRM decides when it is sent

    val notesRequired = stringResource(R.string.after_notes_required)
    val genericError = stringResource(R.string.error_generic)
    val notYourLead = stringResource(R.string.error_not_your_lead)
    val savedText = stringResource(R.string.after_saved)
    val queuedText = stringResource(R.string.queued)
    val label = stringResource(R.string.label_call, name)

    fun selectResult(value: String) {
        result = value
        val next = CallFormLogic.autoNote(value)
        if (notes.isBlank() || notes == autoNote) notes = next
        autoNote = next
        val prefill = CallFormLogic.prefill(value, lead?.followUp)
        when {
            // The suggested date has passed: pre-fill nothing, just point at the quick chips.
            prefill != null && prefill.datePassed -> suggestion = prefill
            prefill != null -> {
                reminder = prefill.reminder
                prefill.newStatusId?.let { newStatusId = it }
                suggestion = prefill
            }
            suggestion != null -> {
                if (suggestion?.archive == true) newStatusId = null
                reminder = ReminderChoice.Keep
                suggestion = null
            }
        }
    }

    fun save() {
        val id = leadId ?: return
        if (notes.isBlank()) {
            error = notesRequired
            return
        }
        error = null
        saving = true
        val body = logCallBody(result, notes.trim(), newStatusId, reminder)
        scope.launch {
            try {
                Services.api.logCall(id, body)
                callId?.let {
                    withContext(Dispatchers.IO) { Services.localDb.markLogged(it) }
                    CallNotifier.cancelForCall(context, it)
                }
                Services.outbox.refresh()
                Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                onDone()
            } catch (e: ApiException.Network) {
                Services.outbox.enqueue(Outbox.KIND_CALL, ApiClient.callsPath(id), body.toString(), label, callId)
                callId?.let { CallNotifier.cancelForCall(context, it) }
                Toast.makeText(context, queuedText, Toast.LENGTH_LONG).show()
                onDone()
            } catch (e: ApiException.Http) {
                error = e.userMessage(notYourLead, genericError)
            } finally {
                saving = false
            }
        }
    }

    ScreenColumn(title = stringResource(R.string.after_title), onBack = onBack) {
        if (loading) {
            CircularProgressIndicator()
            return@ScreenColumn
        }

        // Who and which call
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        val company = (lead?.company ?: local?.company)?.takeIf { it.isNotBlank() && it != name }
        val status = lead?.status?.label ?: local?.status
        val owner = lead?.assignedTo?.name ?: local?.owner
        Text(
            listOfNotNull(company, status, owner, Formats.phone(phone)).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        call?.let { c ->
            Text(
                if (c.answered) stringResource(R.string.after_duration, Formats.ltr(duration(c.durationSeconds))) else stringResource(R.string.recent_missed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        if (offline) Text(stringResource(R.string.after_offline), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)

        if (!found || leadId == null) {
            Text(stringResource(R.string.after_not_in_crm))
            Button(onClick = { onOpenNewLead(phone, callId) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lead_add)) }
            return@ScreenColumn
        }
        if (!fullAccess) {
            Text(stringResource(R.string.after_limited, owner ?: "—"))
            return@ScreenColumn
        }

        SectionLabel(stringResource(R.string.after_result))
        ChoiceChips(CallFormLogic.RESULTS.map { it to Labels.callResult(it) }, result) { selectResult(it) }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it.take(2000) },
            label = { Text(stringResource(R.string.after_notes)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel(stringResource(R.string.after_status))
        Box {
            OutlinedButton(onClick = { statusMenu = true }, modifier = Modifier.fillMaxWidth()) {
                val current = statuses.firstOrNull { it.id == newStatusId }?.name
                Text((current ?: stringResource(R.string.after_status_keep)) + " ▾")
            }
            DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.after_status_keep)) }, onClick = { newStatusId = null; statusMenu = false })
                statuses.forEach { s ->
                    DropdownMenuItem(text = { Text(s.name) }, onClick = { newStatusId = s.id; statusMenu = false })
                }
            }
        }

        SectionLabel(stringResource(R.string.after_reminder))
        Text(
            stringResource(
                R.string.after_reminder_current,
                lead?.nextReminder?.let(Formats::dateTime) ?: stringResource(R.string.after_reminder_none),
            ),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val reminderOptions: List<Pair<ReminderChoice, String>> = listOf(
            ReminderChoice.Keep to stringResource(R.string.after_reminder_keep),
            ReminderChoice.InBusinessDays(3) to stringResource(R.string.after_reminder_3),
            ReminderChoice.InBusinessDays(5) to stringResource(R.string.after_reminder_5),
            ReminderChoice.InBusinessDays(7) to stringResource(R.string.after_reminder_7),
            ReminderChoice.InBusinessDays(14) to stringResource(R.string.after_reminder_14),
            ReminderChoice.InBusinessDays(30) to stringResource(R.string.after_reminder_30),
            ReminderChoice.Clear to stringResource(R.string.after_reminder_clear),
        )
        val suggestedDatePassed = suggestion?.datePassed == true
        ChoiceChips(
            reminderOptions,
            reminder,
            highlight = { suggestedDatePassed && it is ReminderChoice.InBusinessDays },
        ) { reminder = it; suggestion = null }
        TextButton(onClick = { showDatePicker = true }) { Text(stringResource(R.string.after_reminder_pick)) }
        (reminder as? ReminderChoice.At)?.let {
            Text(stringResource(R.string.after_reminder_chosen, Formats.dateTime(it.isoUtc)), fontWeight = FontWeight.SemiBold)
        }
        suggestion?.let {
            Text(
                stringResource(
                    when {
                        it.archive -> R.string.after_archive_suggested
                        it.datePassed -> R.string.after_reminder_passed
                        else -> R.string.after_reminder_suggested
                    },
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Spacer(Modifier.height(4.dp))
        Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.after_save), fontSize = 16.sp)
            }
        }
        OutlinedButton(onClick = { onOpenRequest(leadId, name) }, modifier = Modifier.fillMaxWidth()) {
            Text("+ " + stringResource(R.string.lead_add_request))
        }
    }

    // Date, then time (Algiers clock) for «تاريخ آخر…»
    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = Instant.now().plusSeconds(86_400).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = dateState.selectedDateMillis
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = dateState) }
    }
    pickedDate?.let { day ->
        val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            confirmButton = {
                TextButton(onClick = {
                    reminder = ReminderChoice.At(CallFormLogic.reminderAt(day, timeState.hour, timeState.minute).toString())
                    suggestion = null
                    pickedDate = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = timeState) },
        )
    }
}

/** 252 -> "4:12". */
internal fun duration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
