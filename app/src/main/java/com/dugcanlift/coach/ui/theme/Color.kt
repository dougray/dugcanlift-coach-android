package com.dugcanlift.coach.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.dugcanlift.kit.DclPalette

/**
 * The palette, resolved for the appearance currently drawn.
 *
 * Getters rather than constants: screens and charts use these directly, and as
 * fixed values they would stay dark on a light screen. Read them in composition
 * -- a Canvas draw lambda must capture the colour first.
 */
private fun pick(dark: Long, light: Long, isDark: Boolean) = Color(if (isDark) dark else light)

val DclBg: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.BG, DclPalette.BG_LIGHT, LocalDclDark.current)
val DclSurface: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.SURFACE, DclPalette.SURFACE_LIGHT, LocalDclDark.current)
val DclText: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.TEXT, DclPalette.TEXT_LIGHT, LocalDclDark.current)
val DclMuted: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.MUTED, DclPalette.MUTED_LIGHT, LocalDclDark.current)
val DclAccent: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.ACCENT, DclPalette.ACCENT_LIGHT, LocalDclDark.current)
val DclAccent2: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.ACCENT2, DclPalette.ACCENT2_LIGHT, LocalDclDark.current)
val DclRule: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.RULE, DclPalette.RULE_LIGHT, LocalDclDark.current)
val DclOnAccent: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.ON_ACCENT, DclPalette.ON_ACCENT_LIGHT, LocalDclDark.current)
