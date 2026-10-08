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
/**
 * The brand rust for text and tints: the kit's ACCENT_TEXT. Dark ACCENT (#C1442C) is 3.1:1 on the
 * dark surface; this is #E0674D, 4.69:1 on SURFACE and 5.09:1 on BG. Light equals ACCENT_LIGHT
 * (5.75:1 / 5.14:1). [DclAccent] stays the fill: bars, lines, the selected row's tint.
 */
val DclAccentText: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.ACCENT_TEXT, DclPalette.ACCENT_TEXT_LIGHT, LocalDclDark.current)
val DclAccent2: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.ACCENT2, DclPalette.ACCENT2_LIGHT, LocalDclDark.current)
val DclRule: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.RULE, DclPalette.RULE_LIGHT, LocalDclDark.current)
val DclOnAccent: Color @Composable @ReadOnlyComposable get() = pick(DclPalette.ON_ACCENT, DclPalette.ON_ACCENT_LIGHT, LocalDclDark.current)
