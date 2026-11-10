package com.saascom.caller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.data.CallRecord
import com.saascom.caller.data.PendingAction
import com.saascom.caller.util.Formats
import com.saascom.caller.util.Permissions
import kotlinx.coroutines.launch
import java.time.Instant

/** First tab: calls the app saw (newest first), unsent writes, and a warning if setup is incomplete. */
@Composable
fun RecentCallsScreen(onOpenCall: (CallRecord) -> Unit, onOpenOnboarding: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calls by Services.outbox.calls.collectAsState()
    val outbox by Services.outbox.items.collectAsState()
    var ready by remember { mutableStateOf(Permissions.isReady(context)) }

    LaunchedEffect(Unit) { Services.outbox.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ready = Permissions.isReady(context)
        scope.launch { Services.outbox.refresh() }
    }

    val waiting = outbox.filter { it.lastError == null }
    val refused = outbox.filter { it.lastError != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(stringResource(R.string.recent_title), fontSize = 24.sp, fontWeight = FontWeight.Bold) }

        if (!ready) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenOnboarding),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.home_missing), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.home_missing_desc), fontSize = 13.sp)
                    }
                }
            }
        }

        if (waiting.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.outbox_waiting, waiting.size), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { Services.outbox.flush() } }) { Text(stringResource(R.string.outbox_retry)) }
                    }
                }
            }
        }

        items(refused, key = { "outbox-${it.id}" }) { action -> RefusedRow(action) { scope.launch { Services.outbox.delete(action.id) } } }

        if (calls.isEmpty()) {
            item { Text(stringResource(R.string.recent_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(calls, key = { "call-${it.id}" }) { call -> CallRow(call) { onOpenCall(call) } }
    }
}

@Composable
private fun CallRow(call: CallRecord, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    call.name ?: Formats.phone(call.phone),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    Text(
                        Formats.relative(Instant.ofEpochMilli(call.at).toString()),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(" · ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (call.answered) {
                        Text(Formats.ltr(duration(call.durationSeconds)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(stringResource(R.string.recent_missed), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                call.leadId == null -> Badge(stringResource(R.string.recent_not_in_crm), MaterialTheme.colorScheme.onSurfaceVariant)
                call.answered && !call.logged -> Badge(stringResource(R.string.recent_unlogged), Color(0xFFB7791F))
            }
        }
    }
}

@Composable
private fun RefusedRow(action: PendingAction, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(action.label, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.outbox_refused, action.lastError.orEmpty()), fontSize = 13.sp)
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.outbox_delete)) }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
