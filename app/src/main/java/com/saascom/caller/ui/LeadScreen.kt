package com.saascom.caller.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.saascom.caller.AppIntents
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.call.CardState
import com.saascom.caller.call.toLocal
import com.saascom.caller.data.ApiException
import com.saascom.caller.ui.components.CallerCard
import com.saascom.caller.ui.components.ScreenColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The full client card inside the app, with the actions an agent needs. */
@Composable
fun LeadScreen(
    phone: String,
    onBack: () -> Unit,
    onLogCall: (phone: String) -> Unit,
    onOpenRequest: (leadId: Long, name: String) -> Unit,
    onOpenNewLead: (phone: String) -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<CardState?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(phone, reload) {
        val local = withContext(Dispatchers.IO) { Services.directory.find(phone) }?.toLocal()
        if (local != null && state == null) state = CardState.Loading(phone, local)
        state = try {
            CardState.fromLookup(phone, Services.api.lookup(phone))
        } catch (e: ApiException) {
            local?.let { CardState.Offline(phone, it) } ?: CardState.Unknown(phone)
        }
        refreshing = false
    }

    ScreenColumn(
        title = stringResource(R.string.notif_open_card),
        onBack = onBack,
        refreshing = refreshing,
        onRefresh = {
            refreshing = true
            reload++
        },
    ) {
        val current = state
        if (current == null) {
            CircularProgressIndicator()
            return@ScreenColumn
        }
        CallerCard(state = current, onClose = onBack, onOpenUrl = { openUrl(context, it) })

        Button(onClick = { context.startActivity(AppIntents.dialIntent(phone)) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.lead_call))
        }

        when (current) {
            is CardState.Unknown -> FilledTonalButton(onClick = { onOpenNewLead(phone) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lead_add))
            }
            is CardState.Full -> {
                val lead = current.lead
                val name = lead.name ?: lead.company ?: phone
                FilledTonalButton(onClick = { onLogCall(phone) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lead_log_call))
                }
                OutlinedButton(onClick = { onOpenRequest(lead.id, name) }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ " + stringResource(R.string.lead_add_request))
                }
            }
            is CardState.Offline, is CardState.Loading -> FilledTonalButton(onClick = { onLogCall(phone) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lead_log_call))
            }
            is CardState.Limited -> Unit // another agent's client: theirs to log
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // Nothing can open it.
    }
}
