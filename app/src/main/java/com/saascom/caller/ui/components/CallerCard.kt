package com.saascom.caller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saascom.caller.R
import com.saascom.caller.call.CardState
import com.saascom.caller.call.LocalInfo
import com.saascom.caller.data.LeadCardDto
import com.saascom.caller.util.Formats
import com.saascom.caller.util.Labels

/** The client card: over the call screen (overlay) and inside the app. */
@Composable
fun CallerCard(
    state: CardState,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
            Header(stage = state.stage(), stageLabel = state.stageLabel(), onClose = onClose)
            Spacer(Modifier.height(4.dp))
            when (state) {
                is CardState.Loading -> {
                    LocalBody(state.phone, state.local)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
                    Hint(stringResource(R.string.card_loading))
                }
                is CardState.Offline -> {
                    LocalBody(state.phone, state.local)
                    Hint(stringResource(R.string.card_offline), warning = true)
                }
                is CardState.Unknown -> {
                    Title(stringResource(R.string.card_unknown_title))
                    Subtitle(Formats.phone(state.phone))
                    Hint(stringResource(R.string.card_unknown_sub))
                }
                is CardState.Limited -> {
                    Title(state.lead.displayName(state.phone))
                    Subtitle(listOfNotNull(state.lead.company.takeIf { state.lead.name != null }, Formats.phone(state.phone)).joinToString(" · "))
                    val owner = state.lead.assignedTo?.name ?: stringResource(R.string.card_no_owner)
                    Alert(stringResource(R.string.card_limited, owner), tone = AlertTone.Info)
                }
                is CardState.Full -> FullBody(state.phone, state.lead, onOpenUrl)
            }
        }
    }
}

@Composable
private fun Header(stage: String?, stageLabel: String?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(6.dp))
        Text("SAASCOM", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (stage != null && stageLabel != null) {
            Spacer(Modifier.width(8.dp))
            StageChip(stage, stageLabel)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClose, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) {
            Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StageChip(stage: String, label: String) {
    val color = Color(Labels.stageColor(stage))
    Text(
        text = label,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun LocalBody(phone: String, local: LocalInfo) {
    Title(displayName(local.name, local.company, phone))
    Subtitle(listOfNotNull(local.company.takeIf { local.name != null }, Formats.phone(phone)).joinToString(" · "))
    InfoRow(stringResource(R.string.card_owner), local.owner ?: stringResource(R.string.card_no_owner))
}

@Composable
private fun FullBody(phone: String, lead: LeadCardDto, onOpenUrl: (String) -> Unit) {
    Title(lead.displayName(phone))
    Subtitle(
        listOfNotNull(
            lead.company.takeIf { lead.name != null },
            lead.wilaya,
            Formats.phone(phone),
        ).joinToString(" · "),
    )

    InfoRow(stringResource(R.string.card_owner), lead.assignedTo?.name ?: stringResource(R.string.card_no_owner))

    lead.lastCall?.let { call ->
        InfoRow(
            stringResource(R.string.card_last_call),
            listOf(Formats.relative(call.at), Labels.callResult(call.result)).filter { it.isNotEmpty() }.joinToString(" · "),
        )
        call.note?.takeIf { it.isNotBlank() }?.let { Quote(it) }
    }

    lead.nextReminder?.let { InfoRow(stringResource(R.string.card_reminder), Formats.dateTime(it)) }

    lead.subscription?.let { sub ->
        val value = if (sub.remaining > 0) stringResource(R.string.card_remaining, Formats.money(sub.remaining))
        else stringResource(R.string.card_paid_up)
        InfoRow(stringResource(R.string.card_subscription), value)
    }

    if (lead.openRequestsCount > 0) {
        val first = lead.openRequests.firstOrNull()
        val text = buildString {
            append(stringResource(R.string.card_requests, lead.openRequestsCount))
            first?.let { append(" · ").append(it.title) }
            first?.priority?.let { append(" · ").append(Labels.priority(it)) }
        }
        Alert(text, tone = if (first?.priority == "urgent") AlertTone.Danger else AlertTone.Info)
    }

    val crm = lead.crmUrl
    val whatsapp = lead.whatsappUrl
    if (crm != null || whatsapp != null) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            crm?.let { OutlinedButton(onClick = { onOpenUrl(it) }) { Text(stringResource(R.string.card_open_crm)) } }
            whatsapp?.let { OutlinedButton(onClick = { onOpenUrl(it) }) { Text(stringResource(R.string.card_whatsapp)) } }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Subtitle(text: String) {
    if (text.isNotEmpty()) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Quote(text: String) {
    Text(
        text = "«$text»",
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

private enum class AlertTone { Info, Danger }

@Composable
private fun Alert(text: String, tone: AlertTone) {
    val (bg, fg) = when (tone) {
        AlertTone.Danger -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        AlertTone.Info -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (warning) Color(0xFFA86A12) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private fun displayName(name: String?, company: String?, phone: String): String =
    name?.takeIf { it.isNotBlank() } ?: company?.takeIf { it.isNotBlank() } ?: Formats.phone(phone)

private fun LeadCardDto.displayName(phone: String) = displayName(name, company, phone)

private fun CardState.stage(): String? = when (this) {
    is CardState.Loading -> local.stage
    is CardState.Offline -> local.stage
    is CardState.Full -> lead.status.stage
    is CardState.Limited -> lead.status.stage
    is CardState.Unknown -> null
}

private fun CardState.stageLabel(): String? = when (this) {
    is CardState.Loading -> local.status
    is CardState.Offline -> local.status
    is CardState.Full -> lead.status.label
    is CardState.Limited -> lead.status.label
    is CardState.Unknown -> null
}
