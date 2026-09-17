package com.dugcanlift.coach

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.ui.adaptive.LocalWindowWidth
import com.dugcanlift.coach.ui.adaptive.WindowWidth
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The open client across an activity recreation (a density or theme change) that also crosses the
 * two-pane width. [StateRestorationTester] disposes the composition and rebuilds it from saved
 * state, as a recreation does, and the new composition starts at the new width -- unlike a live
 * resize, where the old composition sees the width change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class RosterSelectionRecreationTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: java.io.File
    private lateinit var repo: ClientRepository
    /**
     * A plain field, not state: the composition being torn down must never see the new width, or
     * this would test a live resize. Only the rebuilt composition reads it.
     */
    private var width = WindowWidth.EXPANDED
    private lateinit var nav: NavHostController

    @Before fun setUp() {
        root = Files.createTempDirectory("coach-recreation").toFile()
        repo = ClientRepository(root)
        repo.save(Client("client-a", "Jordan Reyes", "lb", null, 0, null, emptyList()))
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun start(initial: WindowWidth): StateRestorationTester {
        width = initial
        val tester = StateRestorationTester(compose)
        tester.setContent {
            CompositionLocalProvider(LocalWindowWidth provides width) {
                nav = rememberNavController()
                CoachNavHost(repo = repo, navController = nav)
            }
        }
        return tester
    }

    private fun openJordan() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jordan Reyes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Jordan Reyes").performClick()
        compose.waitForIdle()
    }

    private fun route() = nav.currentBackStackEntry?.destination?.route

    private fun openClientId() = Routes.decodeClientId(
        nav.currentBackStackEntry?.arguments?.getString(Routes.CLIENT_ID_ARG).orEmpty()
    )

    @Test fun expandedSelectionOpensTheClientScreenWhenRecreatedAtMedium() {
        val tester = start(WindowWidth.EXPANDED)
        openJordan()
        assertEquals(Routes.ROSTER, route())

        width = WindowWidth.MEDIUM
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(Routes.CLIENT, route())
        assertEquals("client-a", openClientId())
    }

    @Test fun mediumClientScreenMovesToTheDetailPaneWhenRecreatedExpanded() {
        val tester = start(WindowWidth.MEDIUM)
        openJordan()
        assertEquals(Routes.CLIENT, route())

        width = WindowWidth.EXPANDED
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(Routes.ROSTER, route())
        // Selected in the list and shown in the detail pane: the name is drawn in both.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jordan Reyes").fetchSemanticsNodes().size >= 2 }
    }

    @Test fun recreationWithinMediumKeepsTheClientScreen() {
        val tester = start(WindowWidth.MEDIUM)
        openJordan()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(Routes.CLIENT, route())
    }

    @Test fun backToTheRosterAtMediumStillClearsTheSelectionAcrossRecreation() {
        val tester = start(WindowWidth.MEDIUM)
        openJordan()
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(Routes.ROSTER, route())
    }
}
