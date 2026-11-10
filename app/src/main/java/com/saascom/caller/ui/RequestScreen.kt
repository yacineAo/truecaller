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
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.data.ApiClient
import com.saascom.caller.data.ApiException
import com.saascom.caller.data.ApiJson
import com.saascom.caller.data.CreateRequestRequest
import com.saascom.caller.data.Outbox
import com.saascom.caller.ui.components.ChoiceChips
import com.saascom.caller.ui.components.ScreenColumn
import com.saascom.caller.ui.components.SectionLabel
import com.saascom.caller.util.Labels
import kotlinx.coroutines.launch

/** «طلب عميل»: an SAV request, stored in the CRM as a client-request note (NotesBoard). */
@Composable
fun RequestScreen(leadId: Long, leadName: String, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val genericError = stringResource(R.string.error_generic)
    val notYourLead = stringResource(R.string.error_not_your_lead)
    val sentText = stringResource(R.string.request_sent)
    val queuedText = stringResource(R.string.queued)
    val label = stringResource(R.string.label_request, leadName)

    fun send() {
        if (title.isBlank() || details.isBlank()) {
            error = genericError
            return
        }
        error = null
        saving = true
        val body = CreateRequestRequest(title.trim(), details.trim(), priority)
        scope.launch {
            try {
                Services.api.createRequest(leadId, body)
                Toast.makeText(context, sentText, Toast.LENGTH_SHORT).show()
                onDone()
            } catch (e: ApiException.Network) {
                val json = ApiJson.encodeToString(CreateRequestRequest.serializer(), body)
                Services.outbox.enqueue(Outbox.KIND_REQUEST, ApiClient.requestsPath(leadId), json, label, null)
                Toast.makeText(context, queuedText, Toast.LENGTH_LONG).show()
                onDone()
            } catch (e: ApiException.Http) {
                error = e.userMessage(notYourLead, genericError)
            } finally {
                saving = false
            }
        }
    }

    ScreenColumn(title = stringResource(R.string.request_title), onBack = onBack) {
        SectionLabel(stringResource(R.string.request_for, leadName))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(180) },
            label = { Text(stringResource(R.string.request_subject)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = details,
            onValueChange = { details = it.take(10_000) },
            label = { Text(stringResource(R.string.request_details)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel(stringResource(R.string.request_priority))
        ChoiceChips(listOf("normal", "high", "urgent").map { it to Labels.priority(it) }, priority) { priority = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::send, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.request_send))
            }
        }
    }
}
