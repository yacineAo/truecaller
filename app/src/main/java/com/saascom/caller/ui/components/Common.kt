package com.saascom.caller.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saascom.caller.R

/**
 * A full page: «رجوع», a title, then scrolling content.
 * With [onRefresh] the page can be pulled down to reload; [refreshing] shows the spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenColumn(
    title: String,
    onBack: (() -> Unit)?,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val page: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            onBack?.let { TextButton(onClick = it) { Text(stringResource(R.string.back)) } }
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        if (onRefresh == null) {
            page()
        } else {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { page() }
        }
    }
}

/** A small label above a group of fields. */
@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Single-choice chips that wrap on small screens. [highlight] outlines the chips to point the user at. */
@Composable
fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: T?,
    highlight: (T) -> Boolean = { false },
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                border = if (highlight(value) && !isSelected) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected)
                },
            )
        }
    }
}
