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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.saascom.caller.Services
import com.saascom.caller.data.DirectoryEntry
import com.saascom.caller.util.Formats
import com.saascom.caller.util.Labels
import kotlinx.coroutines.delay

/** Second tab: find a client in the local list by number or name (works offline). */
@Composable
fun SearchScreen(onOpen: (phone: String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DirectoryEntry>>(emptyList()) }
    val sync by Services.directory.info.collectAsState()

    LaunchedEffect(query) {
        delay(200) // wait for the user to stop typing
        results = Services.directory.search(query)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(stringResource(R.string.tab_search), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            query.isBlank() -> item {
                Text(stringResource(R.string.search_intro, sync.count), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            results.isEmpty() -> item { Text(stringResource(R.string.search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(results, key = { it.phone }) { entry -> ResultRow(entry) { onOpen(entry.phone) } }
    }
}

@Composable
private fun ResultRow(entry: DirectoryEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name ?: entry.company ?: Formats.phone(entry.phone),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(entry.company.takeIf { entry.name != null }, Formats.phone(entry.phone), entry.owner).joinToString(" · "),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val color = Color(Labels.stageColor(entry.stage))
            Text(
                entry.status,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}
