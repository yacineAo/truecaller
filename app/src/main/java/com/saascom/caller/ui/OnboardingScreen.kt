package com.saascom.caller.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.util.Permissions

/** One setup step: [done] is re-checked every time the screen comes back (onResume). */
private data class Step(
    val title: Int,
    val description: Int,
    val done: Boolean,
    val required: Boolean,
    val action: () -> Unit,
)

@Composable
fun OnboardingScreen(onDone: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    // Bumped on resume and after each system dialog, so every check runs again.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val afterResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    var asked by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // After "don't ask again" Android no longer shows the dialog: open the app settings instead.
        val permission = asked
        if (!granted && permission != null && (context as? Activity)?.shouldShowRequestPermissionRationale(permission) == false) {
            context.startSafely(Permissions.appDetailsIntent(context))
        }
        refresh++
    }
    val askPermission: (String) -> Unit = { permission ->
        asked = permission
        permissionLauncher.launch(permission)
    }

    val steps = remember(refresh) { buildList {
        add(
            Step(R.string.step_role, R.string.step_role_desc, Permissions.roleHeld(context), required = true) {
                if (Permissions.roleAvailable(context)) afterResult.launch(Permissions.roleRequestIntent(context))
            },
        )
        add(
            Step(R.string.step_overlay, R.string.step_overlay_desc, Permissions.overlay(context), required = true) {
                afterResult.launch(Permissions.overlayIntent(context))
            },
        )
        add(
            Step(R.string.step_phone, R.string.step_phone_desc, Permissions.phoneState(context), required = true) {
                askPermission(Manifest.permission.READ_PHONE_STATE)
            },
        )
        add(
            Step(R.string.step_contacts, R.string.step_contacts_desc, Permissions.contacts(context), required = true) {
                askPermission(Manifest.permission.READ_CONTACTS)
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                Step(R.string.step_notifications, R.string.step_notifications_desc, Permissions.notifications(context), required = false) {
                    askPermission(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
        }
        add(
            Step(R.string.step_battery, R.string.step_battery_desc, Permissions.batteryUnrestricted(context), required = false) {
                context.startSafely(Permissions.batteryIntent(context))
            },
        )
        if (Permissions.needsAutostart()) {
            add(
                Step(R.string.step_autostart, R.string.step_autostart_desc, Services.settings.autostartConfirmed, required = false) {
                    Permissions.autostartIntents(context).firstOrNull { context.startSafely(it) }
                },
            )
        }
    } }
    val ready = steps.filter { it.required }.all { it.done }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        onBack?.let { TextButton(onClick = it) { Text(stringResource(R.string.back)) } }
        Text(stringResource(R.string.onboarding_title), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.onboarding_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!Permissions.roleAvailable(context)) {
            Text(stringResource(R.string.step_role_unavailable), color = MaterialTheme.colorScheme.error)
        }

        steps.forEachIndexed { index, step ->
            StepRow(
                number = index + 1,
                step = step,
                onConfirm = if (step.title == R.string.step_autostart) {
                    { Services.settings.autostartConfirmed = true; refresh++ }
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(4.dp))
        if (!ready) {
            Text(stringResource(R.string.onboarding_required_missing), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Button(onClick = onDone, enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.onboarding_continue), fontSize = 16.sp)
        }
    }
}

@Composable
private fun StepRow(number: Int, step: Step, onConfirm: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (step.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (step.done) "✓" else number.toString(),
                    color = if (step.done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(step.title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(step.description), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!step.done) {
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalButton(onClick = step.action) { Text(stringResource(R.string.enable)) }
                    onConfirm?.let { TextButton(onClick = it) { Text(stringResource(R.string.done)) } }
                }
            }
        }
    }
}

/** startActivity that returns false instead of crashing when a brand screen does not exist. */
private fun Context.startSafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
