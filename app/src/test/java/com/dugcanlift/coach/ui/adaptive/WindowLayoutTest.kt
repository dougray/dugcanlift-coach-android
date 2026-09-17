package com.dugcanlift.coach.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The width-to-layout mapping. Pure functions, so no Robolectric and no store singletons to reset.
 * The widths used are the real ones: a Pixel 8 portrait is 411 dp, landscape 914 dp; a 2560x1600
 * tablet at 320 dpi is 1280 x 800 dp; an unfolded foldable is about 841 dp wide.
 */
class WindowLayoutTest {

    @Test fun `size classes break at 600 and 840 dp`() {
        assertEquals(WindowWidth.COMPACT, WindowWidth.fromDp(0f))
        assertEquals(WindowWidth.COMPACT, WindowWidth.fromDp(411f))
        assertEquals(WindowWidth.COMPACT, WindowWidth.fromDp(599.9f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.fromDp(600f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.fromDp(800f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.fromDp(839.9f))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.fromDp(840f))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.fromDp(1280f))
    }

    @Test fun `a phone keeps its bottom bar and back buttons, and never splits the roster`() {
        assertFalse(AdaptiveLayout.usesNavigationRail(WindowWidth.COMPACT))
        assertTrue(AdaptiveLayout.showsBackOnTopLevelScreens(WindowWidth.COMPACT))
        assertFalse(AdaptiveLayout.rosterIsTwoPane(WindowWidth.COMPACT))
    }

    @Test fun `medium gets the rail but a single roster pane`() {
        assertTrue(AdaptiveLayout.usesNavigationRail(WindowWidth.MEDIUM))
        assertFalse(AdaptiveLayout.showsBackOnTopLevelScreens(WindowWidth.MEDIUM))
        assertFalse(AdaptiveLayout.rosterIsTwoPane(WindowWidth.MEDIUM))
    }

    @Test fun `expanded gets the rail and list plus detail`() {
        assertTrue(AdaptiveLayout.usesNavigationRail(WindowWidth.EXPANDED))
        assertTrue(AdaptiveLayout.rosterIsTwoPane(WindowWidth.EXPANDED))
    }

    @Test fun `with no hinge the list pane is 360 dp, or 45 percent when that is narrower`() {
        assertEquals(PaneSplit(360f, 0f), AdaptiveLayout.listDetailSplit(80f, 1200f, null))
        assertEquals(PaneSplit(342f, 0f), AdaptiveLayout.listDetailSplit(80f, 760f, null))
    }

    @Test fun `a separating vertical hinge moves the split onto the fold`() {
        // 841 dp window, rail to 80 dp, fold at 420..421 dp.
        val split = AdaptiveLayout.listDetailSplit(80f, 761f, VerticalHinge(420f, 421f))
        assertEquals(340f, split.listWidthDp, 0.001f)
        assertEquals(1f, split.gapDp, 0.001f)
    }

    @Test fun `a hinge that would leave a pane unusably narrow is ignored`() {
        // Fold 200 dp from the pane's edge: a 200 dp list is too narrow.
        assertEquals(PaneSplit(342f, 0f), AdaptiveLayout.listDetailSplit(80f, 760f, VerticalHinge(280f, 280f)))
        // Fold near the far edge: the detail would be under 320 dp.
        assertEquals(PaneSplit(342f, 0f), AdaptiveLayout.listDetailSplit(80f, 760f, VerticalHinge(600f, 600f)))
        // Fold outside the pane entirely (left of it).
        assertEquals(PaneSplit(342f, 0f), AdaptiveLayout.listDetailSplit(80f, 760f, VerticalHinge(40f, 41f)))
    }

    @Test fun `a client page goes two-column from a 600 dp page, capped at the content width`() {
        assertEquals(1, AdaptiveLayout.clientColumns(411f))
        assertEquals(1, AdaptiveLayout.clientColumns(599f))
        assertEquals(2, AdaptiveLayout.clientColumns(600f))
        assertEquals(411f, AdaptiveLayout.clientContentWidth(411f), 0f)
        assertEquals(AdaptiveLayout.MAX_CONTENT_DP, AdaptiveLayout.clientContentWidth(2000f), 0f)
    }

    @Test fun `cards are one column on a phone and fill wider space in 280 dp cells`() {
        assertEquals(1, AdaptiveLayout.cardColumns(379f))
        assertEquals(1, AdaptiveLayout.cardColumns(599f))
        assertEquals(2, AdaptiveLayout.cardColumns(600f))
        assertEquals(2, AdaptiveLayout.cardColumns(863f))
        assertEquals(3, AdaptiveLayout.cardColumns(864f))
        assertEquals(4, AdaptiveLayout.cardColumns(1168f))
        assertEquals(4, AdaptiveLayout.cardColumns(4000f))
    }

    @Test fun `plan days sit side by side up to a whole week`() {
        assertEquals(1, AdaptiveLayout.planDayColumns(379f))
        assertEquals(2, AdaptiveLayout.planDayColumns(600f))
        assertEquals(5, AdaptiveLayout.planDayColumns(1168f))
        assertEquals(7, AdaptiveLayout.planDayColumns(3000f))
    }

    @Test fun `shopping is two columns from medium`() {
        assertEquals(1, AdaptiveLayout.shoppingColumns(379f))
        assertEquals(2, AdaptiveLayout.shoppingColumns(600f))
        assertEquals(2, AdaptiveLayout.shoppingColumns(1168f))
    }

    @Test fun `unfolding while a client is open moves the client into the detail pane`() {
        assertEquals(RosterReconcile.SHOW_IN_DETAIL_PANE,
            AdaptiveLayout.reconcileRoster(twoPane = true, widthChanged = true, onClientRoute = true,
                                           onRosterRoute = false, selectedClientId = "a"))
    }

    @Test fun `narrowing with a client in the detail pane opens it full screen`() {
        assertEquals(RosterReconcile.OPEN_CLIENT_SCREEN,
            AdaptiveLayout.reconcileRoster(twoPane = false, widthChanged = true, onClientRoute = false,
                                           onRosterRoute = true, selectedClientId = "a"))
    }

    @Test fun `back to the roster on a phone clears the selection instead of reopening the client`() {
        assertEquals(RosterReconcile.CLEAR_SELECTION,
            AdaptiveLayout.reconcileRoster(twoPane = false, widthChanged = false, onClientRoute = false,
                                           onRosterRoute = true, selectedClientId = "a"))
    }

    @Test fun `nothing to do otherwise`() {
        assertEquals(RosterReconcile.NONE,
            AdaptiveLayout.reconcileRoster(twoPane = true, widthChanged = false, onClientRoute = false,
                                           onRosterRoute = true, selectedClientId = "a"))
        assertEquals(RosterReconcile.NONE,
            AdaptiveLayout.reconcileRoster(twoPane = false, widthChanged = false, onClientRoute = true,
                                           onRosterRoute = false, selectedClientId = "a"))
        assertEquals(RosterReconcile.NONE,
            AdaptiveLayout.reconcileRoster(twoPane = false, widthChanged = true, onClientRoute = false,
                                           onRosterRoute = true, selectedClientId = null))
    }

    @Test fun `row-major fills across then down`() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), rowMajor(listOf(1, 2, 3, 4, 5), 3))
        assertEquals(listOf(listOf(1), listOf(2)), rowMajor(listOf(1, 2), 1))
    }

    @Test fun `column-major reads down each column, earlier columns taking the extra item`() {
        assertEquals(listOf(listOf(1, 4), listOf(2, 5), listOf(3)), columnMajor(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(listOf(1, 3), listOf(2, 4)), columnMajor(listOf(1, 2, 3, 4), 2))
        assertEquals(listOf(listOf(1)), columnMajor(listOf(1), 2))
        assertEquals(emptyList<List<Int>>(), columnMajor(emptyList<Int>(), 2))
    }
}
