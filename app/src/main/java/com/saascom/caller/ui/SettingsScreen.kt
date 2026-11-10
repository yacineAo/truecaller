package com.saascom.caller.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.saascom.caller.BuildConfig
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.call.CallerCardController
import com.saascom.caller.data.AppVersionDto
import com.saascom.caller.util.Formats
import com.saascom.caller.util.Permissions
import kotlinx.coroutines.launch
import java.time.Instant

/** Third tab: readiness, client list, privacy setting, update, logout. */
@Composable
fun SettingsScreen(onOpenOnboarding: () -> Unit, onUpdateFound: (AppVersionDto) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by Services.session.user.collectAsState()
    val sync by Services.directory.info.collectAsState()
    val lookupUnknown by Services.settings.lookupUnknownNumbers.collectAsState()
    var ready by remember { mutableStateOf(Permissions.isReady(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ready = Permissions.isReady(context) }

    val needsOverlay = stringResource(R.string.home_test_card_needs_overlay)
    val upToDate = stringResource(R.string.update_none)
    val networkError = stringResource(R.string.error_network)

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.home_hello, user?.name.orEmpty()), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // Ready / missing permission
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenOnboarding),
            colors = CardDefaults.cardColors(
                containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(stringResource(if (ready) R.string.home_ready else R.string.home_missing), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(if (ready) R.string.home_ready_desc else R.string.home_missing_desc))
            }
        }

        // Client list
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.home_clients, sync.count), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (sync.lastSyncAt > 0) {
                        stringResource(R.string.home_last_sync, Formats.relative(Instant.ofEpochMilli(sync.lastSyncAt).toString()))
                    } else {
                        stringResource(R.string.home_never_synced)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sync.lastError?.let { Text(stringResource(R.string.home_sync_failed), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                FilledTonalButton(onClick = { scope.launch { runCatching { Services.directory.sync() } } }, enabled = !sync.syncing) {
                    if (sync.syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.home_syncing))
                    } else {
                        Text(stringResource(R.string.home_sync))
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                if (Permissions.overlay(context)) {
                    CallerCardController.showSample(context)
                } else {
                    Toast.makeText(context, needsOverlay, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.home_test_card)) }

        // Privacy
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_lookup_unknown), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_lookup_unknown_desc), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = lookupUnknown, onCheckedChange = Services.settings::setLookupUnknownNumbers)
            }
        }

        OutlinedButton(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_permissions)) }
        OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        val newer = AppUpdater.check(force = true)
                        if (newer != null) onUpdateFound(newer) else Toast.makeText(context, upToDate, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, networkError, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_update_check)) }

        Text(stringResource(R.string.settings_server, Formats.ltr(Services.session.baseUrl)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_version, Formats.ltr(BuildConfig.VERSION_NAME)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching { Services.api.logout() } // the phone forgets the user even offline
                    Services.logoutLocally()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error) }
    }
}
