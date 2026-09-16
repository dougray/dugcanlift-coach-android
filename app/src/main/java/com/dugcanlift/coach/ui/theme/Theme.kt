package com.dugcanlift.coach.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.kit.DclPalette
import com.dugcanlift.coach.data.AppAppearance
import com.dugcanlift.coach.data.AppearanceStore

/**
 * Both schemes from DclPalette, which mirrors LiftCore.Theme on iOS.
 *
 * The surfaceContainer* roles are set explicitly in both. Material 3's `Card`
 * paints with `surfaceContainerHighest`, which neither scheme set before, so
 * cards took Material's baseline -- a cool grey in dark and a lavender grey in
 * light -- rather than the brand's warm surface.
 */
private fun scheme(dark: Boolean): ColorScheme {
    val bg = Color(if (dark) DclPalette.BG else DclPalette.BG_LIGHT)
    val surface = Color(if (dark) DclPalette.SURFACE else DclPalette.SURFACE_LIGHT)
    val text = Color(if (dark) DclPalette.TEXT else DclPalette.TEXT_LIGHT)
    val muted = Color(if (dark) DclPalette.MUTED else DclPalette.MUTED_LIGHT)
    val accent = Color(if (dark) DclPalette.ACCENT else DclPalette.ACCENT_LIGHT)
    val accent2 = Color(if (dark) DclPalette.ACCENT2 else DclPalette.ACCENT2_LIGHT)
    val rule = Color(if (dark) DclPalette.RULE else DclPalette.RULE_LIGHT)
    val onAccent = Color(if (dark) DclPalette.ON_ACCENT else DclPalette.ON_ACCENT_LIGHT)

    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = accent, onPrimaryContainer = onAccent,
        secondary = accent2, onSecondary = bg,
        tertiary = accent2, onTertiary = bg,
        secondaryContainer = accent, onSecondaryContainer = onAccent,
        background = bg, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = surface, onSurfaceVariant = muted,
        surfaceContainerLowest = surface, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        outline = rule, outlineVariant = rule,
        error = accent, onError = onAccent
    )
}

/** Whether the app is currently drawing dark, after the person's choice. */
val LocalDclDark = staticCompositionLocalOf { true }

/**
 * The hairline a light card needs and a dark card never had: parchment and
 * near-white are too close in brightness to separate on their own. Null in dark.
 */
@Composable
@ReadOnlyComposable
fun dclCardBorder(): BorderStroke? =
    if (LocalDclDark.current) null else BorderStroke(1.dp, Color(DclPalette.RULE_LIGHT))

@Composable
fun CoachTheme(
    content: @Composable () -> Unit
) {
    val choice by AppearanceStore.get(LocalContext.current).appearance.collectAsState()
    val dark = when (choice) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
    CompositionLocalProvider(LocalDclDark provides dark) {
        MaterialTheme(
            colorScheme = scheme(dark),
            typography = DclTypography,
            content = content
        )
    }
}
