package com.saascom.caller.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saascom.caller.App
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.call.CallNotifier
import com.saascom.caller.data.ApiClient
import com.saascom.caller.data.ApiException
import com.saascom.caller.data.ApiJson
import com.saascom.caller.data.CreateLeadRequest
import com.saascom.caller.data.CreateLeadResult
import com.saascom.caller.data.Outbox
import com.saascom.caller.ui.components.ScreenColumn
import com.saascom.caller.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «عميل جديد» from an unknown caller. The CRM also logs the call, which assigns the lead to the agent. */
@Composable
fun NewLeadScreen(phone: String, callId: Long?, onBack: () -> Unit, onDone: () -> Unit, onOpenLead: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val notesRequired = stringResource(R.string.after_notes_required)
    val genericError = stringResource(R.string.error_generic)
    val savedText = stringResource(R.string.new_lead_saved)
    val existsText = stringResource(R.string.new_lead_exists)
    val queuedText = stringResource(R.string.queued)
    val label = stringResource(R.string.label_lead, name.ifBlank { company.ifBlank { Formats.phone(phone) } })

    fun save() {
        if (notes.isBlank()) {
            error = notesRequired
            return
        }
        error = null
        saving = true
        val body = CreateLeadRequest(phone, name.trim().ifBlank { null }, company.trim().ifBlank { null }, notes.trim())
        scope.launch {
            try {
                when (val result = Services.api.createLead(body)) {
                    is CreateLeadResult.Created -> {
                        attachCallToLead(callId, result.leadId, body)
                        markCallLogged(callId)
                        CallNotifier.cancelForCall(context, callId ?: -1)
                        Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                        // The new number must be in the local list for the next call.
                        (context.applicationContext as App).scope.launch { runCatching { Services.directory.sync() } }
                        onDone()
                    }
                    is CreateLeadResult.Exists -> {
                        attachCallToLead(callId, result.leadId, body)
                        Toast.makeText(context, result.message ?: existsText, Toast.LENGTH_LONG).show()
                        onOpenLead(phone)
                    }
                }
            } catch (e: ApiException.Network) {
                val json = ApiJson.encodeToString(CreateLeadRequest.serializer(), body)
                Services.outbox.enqueue(Outbox.KIND_LEAD, ApiClient.LEADS_PATH, json, label, callId)
                Toast.makeText(context, queuedText, Toast.LENGTH_LONG).show()
                onDone()
            } catch (e: ApiException.Http) {
                error = e.userMessage(forbidden = genericError, fallback = genericError)
            } finally {
                saving = false
            }
        }
    }

    ScreenColumn(title = stringResource(R.string.new_lead_title), onBack = onBack) {
        OutlinedTextField(
            value = Formats.phone(phone),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.new_lead_phone)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(255) },
            label = { Text(stringResource(R.string.new_lead_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = company,
            onValueChange = { company = it.take(255) },
            label = { Text(stringResource(R.string.new_lead_company)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it.take(2000) },
            label = { Text(stringResource(R.string.new_lead_notes)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.new_lead_save))
            }
        }
    }
}

/** The call was from an unknown number: now that the CRM has the lead, the history row must point to it. */
private suspend fun attachCallToLead(callId: Long?, leadId: Long, lead: CreateLeadRequest) {
    callId ?: return
    val name = lead.name?.takeIf { it.isNotBlank() } ?: lead.company?.takeIf { it.isNotBlank() }
    withContext(Dispatchers.IO) { Services.localDb.updateCallLead(callId, leadId, name) }
}

private suspend fun markCallLogged(callId: Long?) {
    callId ?: return
    withContext(Dispatchers.IO) { Services.localDb.markLogged(callId) }
    Services.outbox.refresh()
}
