package com.saascom.caller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val Light = lightColorScheme(
    primary = Color(0xFF2C56E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EDFD),
    onPrimaryContainer = Color(0xFF1B3594),
    background = Color(0xFFF5F6F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5A6474),
    // Cards, tonal buttons and switches: brand tints instead of Material's default purple.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    secondary = Color(0xFF2C56E0),
    secondaryContainer = Color(0xFFE7EDFD),
    onSecondaryContainer = Color(0xFF1B3594),
    outline = Color(0xFFC9CFD8),
    outlineVariant = Color(0xFFE1E5EB),
    error = Color(0xFFC63B3B),
    errorContainer = Color(0xFFFBE6E6),
    onErrorContainer = Color(0xFFA32D2D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF84A2FF),
    onPrimary = Color(0xFF0D1A4A),
    primaryContainer = Color(0xFF1B2546),
    onPrimaryContainer = Color(0xFFDCE4FF),
    background = Color(0xFF0D1015),
    surface = Color(0xFF151920),
    surfaceVariant = Color(0xFF1C212A),
    onSurfaceVariant = Color(0xFF98A2B3),
    surfaceContainerLowest = Color(0xFF151920),
    surfaceContainerLow = Color(0xFF151920),
    surfaceContainer = Color(0xFF181C24),
    surfaceContainerHigh = Color(0xFF1C212A),
    surfaceContainerHighest = Color(0xFF1C212A),
    secondary = Color(0xFF84A2FF),
    secondaryContainer = Color(0xFF1B2546),
    onSecondaryContainer = Color(0xFFDCE4FF),
    outline = Color(0xFF3A414D),
    outlineVariant = Color(0xFF282E39),
    error = Color(0xFFF07A7A),
    errorContainer = Color(0xFF2F1717),
    onErrorContainer = Color(0xFFF7C6C6),
)

/** App theme. The UI is Arabic, so it is right-to-left even on a French or English phone. */
@Composable
fun CallerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
