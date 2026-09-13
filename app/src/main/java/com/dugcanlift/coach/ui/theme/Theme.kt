package com.dugcanlift.coach.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DclColorScheme = darkColorScheme(
    primary = DclAccent,
    onPrimary = DclOnAccent,
    primaryContainer = DclAccent,
    onPrimaryContainer = DclOnAccent,
    secondary = DclAccent2,
    onSecondary = DclBg,
    tertiary = DclAccent2,
    onTertiary = DclBg,
    secondaryContainer = DclAccent,
    onSecondaryContainer = DclOnAccent,
    background = DclBg,
    onBackground = DclText,
    surface = DclSurface,
    onSurface = DclText,
    surfaceVariant = DclSurface,
    onSurfaceVariant = DclMuted,
    outline = DclRule,
    outlineVariant = DclRule,
    error = DclAccent,
    onError = DclOnAccent
)

@Composable
fun CoachTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DclColorScheme,
        typography = DclTypography,
        content = content
    )
}
