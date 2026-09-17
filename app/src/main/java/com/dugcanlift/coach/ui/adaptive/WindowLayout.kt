package com.dugcanlift.coach.ui.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Material 3's window width size classes, decided by the width of the *window* in dp -- never by
 * what kind of device this is. A tablet in a narrow split-screen pane is compact; a phone turned
 * landscape is expanded. Compact is the phone layout, and it must stay exactly as it was.
 */
enum class WindowWidth {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        const val MEDIUM_MIN_DP = 600f
        const val EXPANDED_MIN_DP = 840f

        fun fromDp(widthDp: Float): WindowWidth = when {
            widthDp < MEDIUM_MIN_DP -> COMPACT
            widthDp < EXPANDED_MIN_DP -> MEDIUM
            else -> EXPANDED
        }
    }
}

/**
 * A fold or hinge that divides the window into two sides, as an x range in dp from the window's
 * left edge. Only a *vertical* separating one is modelled: that is the one a side-by-side list and
 * detail can be placed either side of. A horizontal one (table-top) runs across scrolling content,
 * where there is nothing sensible to move out of its way.
 */
data class VerticalHinge(val startDp: Float, val endDp: Float) {
    val widthDp: Float get() = endDp - startDp
}

val LocalWindowWidth = compositionLocalOf { WindowWidth.COMPACT }
val LocalVerticalHinge = compositionLocalOf<VerticalHinge?> { null }

/**
 * Measures the window once, at the root, and provides [LocalWindowWidth] and [LocalVerticalHinge]
 * to everything below. [hingePx] is the separating vertical fold's left and right edge in window
 * pixels, from `WindowInfoTracker` in `MainActivity`, or null when there is none.
 */
@Composable
fun ProvideWindowLayout(hingePx: Pair<Int, Int>?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current.density
        val hinge = hingePx?.let { (left, right) -> VerticalHinge(left / density, right / density) }
        CompositionLocalProvider(
            LocalWindowWidth provides WindowWidth.fromDp(maxWidth.value),
            LocalVerticalHinge provides hinge
        ) {
            content()
        }
    }
}

/** A list pane's width and the gap between it and the detail pane (non-zero only over a hinge). */
data class PaneSplit(val listWidthDp: Float, val gapDp: Float)

/** What the navigation host must do to keep the roster's selection and the back stack in step. */
enum class RosterReconcile {
    NONE,
    /** Wide now, and a client is open full-screen: show it in the detail pane instead. */
    SHOW_IN_DETAIL_PANE,
    /** Narrow now, and a client was showing in the detail pane: open it full-screen. */
    OPEN_CLIENT_SCREEN,
    /** Narrow, and back on the roster (a Back from the client screen): nothing is selected. */
    CLEAR_SELECTION
}

/**
 * Every width-to-layout decision the app makes, as plain functions with no Compose in them, so the
 * mapping is unit tested rather than only ever seen on a screen.
 */
object AdaptiveLayout {
    /** The roster's list pane when it sits beside a client. */
    const val LIST_PANE_DP = 360f
    const val MIN_LIST_PANE_DP = 240f
    const val MIN_DETAIL_PANE_DP = 320f

    /** Past this, a client page stops growing and centres: a 2,000 dp line of text is unreadable. */
    const val MAX_CONTENT_DP = 1040f

    /** Connect is a short form; wider than this its fields are just long lines. */
    const val MAX_FORM_DP = 640f

    /** A primary button in a wide layout; full width at 1,200 dp is a banner, not a button. */
    const val MAX_WIDE_BUTTON_DP = 420f

    const val MIN_CARD_DP = 280f
    const val MIN_DAY_DP = 220f
    const val GRID_GAP_DP = 12f
    const val MAX_CARD_COLUMNS = 4
    const val MAX_DAY_COLUMNS = 7

    /** Roster, Train, Cook and Connect: a bottom bar on a phone, a navigation rail from medium up. */
    fun usesNavigationRail(width: WindowWidth): Boolean = width != WindowWidth.COMPACT

    /** Pushed screens keep their Back button only where there is no rail to leave them by. */
    fun showsBackOnTopLevelScreens(width: WindowWidth): Boolean = width == WindowWidth.COMPACT

    /** The roster becomes list + detail only when expanded; medium is too narrow for two panes. */
    fun rosterIsTwoPane(width: WindowWidth): Boolean = width == WindowWidth.EXPANDED

    /**
     * Where the list pane ends. Normally [LIST_PANE_DP] (never more than 45% of the space), but a
     * separating vertical hinge inside the space moves the split onto the hinge, so neither pane
     * is drawn across it -- as long as both sides are still wide enough to use.
     *
     * @param paneStartDp the two-pane area's left edge in window dp (after the rail).
     */
    fun listDetailSplit(paneStartDp: Float, paneWidthDp: Float, hinge: VerticalHinge?): PaneSplit {
        if (hinge != null) {
            val list = hinge.startDp - paneStartDp
            val detail = paneWidthDp - list - hinge.widthDp
            if (list >= MIN_LIST_PANE_DP && detail >= MIN_DETAIL_PANE_DP) {
                return PaneSplit(list, hinge.widthDp)
            }
        }
        return PaneSplit(minOf(LIST_PANE_DP, paneWidthDp * 0.45f), 0f)
    }

    /** A client page's charts: two columns once the page itself (not the window) is 600 dp wide. */
    fun clientColumns(contentWidthDp: Float): Int = if (contentWidthDp >= WindowWidth.MEDIUM_MIN_DP) 2 else 1

    /** How wide a client page lays out: the pane, capped at [MAX_CONTENT_DP]. */
    fun clientContentWidth(paneWidthDp: Float): Float = minOf(paneWidthDp, MAX_CONTENT_DP)

    /** Recipe, routine and session cards. One column below 600 dp, which is the phone layout. */
    fun cardColumns(availableWidthDp: Float): Int =
        if (availableWidthDp < WindowWidth.MEDIUM_MIN_DP) 1
        else fit(availableWidthDp, MIN_CARD_DP).coerceIn(2, MAX_CARD_COLUMNS)

    /** A week plan's days side by side, up to all seven. */
    fun planDayColumns(availableWidthDp: Float): Int =
        if (availableWidthDp < WindowWidth.MEDIUM_MIN_DP) 1
        else fit(availableWidthDp, MIN_DAY_DP).coerceIn(2, MAX_DAY_COLUMNS)

    /** The shopping list: one column on a phone, two otherwise. */
    fun shoppingColumns(availableWidthDp: Float): Int =
        if (availableWidthDp < WindowWidth.MEDIUM_MIN_DP) 1 else 2

    private fun fit(width: Float, min: Float): Int = floor((width + GRID_GAP_DP) / (min + GRID_GAP_DP)).toInt()

    /**
     * @param widthChanged whether two-pane-ness changed since the last check -- the only thing that
     *   tells a narrow roster reached by resizing (reopen the client) from one reached by Back
     *   (clear the selection). "The last check" includes one made before an activity recreation:
     *   see [rosterTwoPaneToRemember].
     */
    fun reconcileRoster(twoPane: Boolean, widthChanged: Boolean, onClientRoute: Boolean,
                        onRosterRoute: Boolean, selectedClientId: String?): RosterReconcile = when {
        twoPane && onClientRoute -> RosterReconcile.SHOW_IN_DETAIL_PANE
        !twoPane && onRosterRoute && selectedClientId != null ->
            if (widthChanged) RosterReconcile.OPEN_CLIENT_SCREEN else RosterReconcile.CLEAR_SELECTION
        else -> RosterReconcile.NONE
    }

    /**
     * The two-pane-ness the next [reconcileRoster] check compares against, which the navigation host
     * keeps *saveable*. A density or theme change recreates the activity, and the rebuilt
     * composition starts at the new width: remembered only in memory, the old two-pane-ness was
     * lost, so an expanded roster with a client selected, recreated at medium, read as a Back and
     * cleared the client instead of opening it.
     *
     * Saving it is not enough on its own. The first check after a recreation runs before the
     * navigation back stack is restored -- its entry has no destination route yet, so there is
     * nothing to do;
     * recording the new width then would spend the change on a check that could not act on it.
     * So until the back stack is known, the old value is kept.
     */
    fun rosterTwoPaneToRemember(lastTwoPane: Boolean, twoPane: Boolean, backStackRestored: Boolean): Boolean =
        if (backStackRestored) twoPane else lastTwoPane
}

/** [items] in rows of [columns], left to right then down -- cards, which read as a set. */
fun <T> rowMajor(items: List<T>, columns: Int): List<List<T>> = items.chunked(columns.coerceAtLeast(1))

/**
 * [items] in rows of [columns] where each *column* is read top to bottom -- a list, like shopping,
 * that is still meant to be read in order. Earlier columns take the extra item.
 */
fun <T> columnMajor(items: List<T>, columns: Int): List<List<T>> {
    val cols = columns.coerceAtLeast(1)
    if (items.isEmpty()) return emptyList()
    val perColumn = ceil(items.size / cols.toDouble()).toInt()
    val split = items.chunked(perColumn)
    return (0 until perColumn).map { row -> split.mapNotNull { it.getOrNull(row) } }
}
