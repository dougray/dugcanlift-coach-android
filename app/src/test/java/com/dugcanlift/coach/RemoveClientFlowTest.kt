package com.dugcanlift.coach

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.ui.adaptive.LocalWindowWidth
import com.dugcanlift.coach.ui.adaptive.WindowWidth
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Remove client end to end through [CoachNavHost]: from the client page on a phone (back to the
 * roster), from the two-pane detail (selection cleared), and from a roster row's long-press menu.
 * The library clean-up itself is pinned by `ClientRemovalTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class RemoveClientFlowTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: java.io.File
    private lateinit var repo: ClientRepository
    private lateinit var nav: NavHostController

    @Before fun setUp() {
        root = Files.createTempDirectory("coach-remove").toFile()
        repo = ClientRepository(root)
        repo.save(Client("client-a", "Jordan Reyes", "lb", null, 0, null, emptyList()))
        repo.save(Client("client-b", "Sam Ortiz", "lb", null, 0, null, emptyList()))
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun start(width: WindowWidth) {
        compose.setContent {
            CompositionLocalProvider(LocalWindowWidth provides width) {
                nav = rememberNavController()
                CoachNavHost(repo = repo, navController = nav)
            }
        }
    }

    private fun waitForText(text: String, atLeast: Int = 1) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size >= atLeast }

    private fun confirmRemoval() {
        waitForText("Remove Jordan Reyes?")
        compose.onNodeWithText("will be removed from this device", substring = true).assertExists()
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { repo.get("client-a") == null }
        compose.waitForIdle()
    }

    private fun jordanGone() {
        assertNull(repo.get("client-a"))
        assertEquals(listOf("client-b"), repo.all().map { it.id })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jordan Reyes").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun removingFromThePhoneClientScreenReturnsToTheRoster() {
        start(WindowWidth.COMPACT)
        waitForText("Jordan Reyes")
        compose.onNodeWithText("Jordan Reyes").performClick()
        compose.waitForIdle()
        assertEquals(Routes.CLIENT, nav.currentBackStackEntry?.destination?.route)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Remove this client"))
        compose.onNodeWithText("Remove this client").performClick()
        confirmRemoval()

        assertEquals(Routes.ROSTER, nav.currentBackStackEntry?.destination?.route)
        jordanGone()
        waitForText("Sam Ortiz")
    }

    @Test fun keepLeavesTheClientAlone() {
        start(WindowWidth.COMPACT)
        waitForText("Jordan Reyes")
        compose.onNodeWithText("Jordan Reyes").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Remove this client"))
        compose.onNodeWithText("Remove this client").performClick()
        waitForText("Remove Jordan Reyes?")
        compose.onNodeWithText("Keep").performClick()
        compose.waitForIdle()

        assertEquals("Jordan Reyes", repo.get("client-a")?.name)
        assertEquals(Routes.CLIENT, nav.currentBackStackEntry?.destination?.route)
    }

    @Test fun removingFromTheTwoPaneDetailClearsTheSelection() {
        start(WindowWidth.EXPANDED)
        waitForText("Jordan Reyes")
        compose.onNodeWithText("Jordan Reyes").performClick()
        // Selected: drawn in the list and as the detail pane's title.
        waitForText("Jordan Reyes", atLeast = 2)

        // Two scrolling lists side by side; the client page is the second.
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText("Remove this client"))
        compose.onNodeWithText("Remove this client").performClick()
        confirmRemoval()

        assertEquals(Routes.ROSTER, nav.currentBackStackEntry?.destination?.route)
        jordanGone()
        waitForText("Pick a client to see their training.")
    }

    @Test fun longPressingARosterRowOffersRemove() {
        start(WindowWidth.COMPACT)
        waitForText("Jordan Reyes")
        compose.onNodeWithText("Jordan Reyes").performTouchInput { longClick() }
        waitForText("Remove client")
        compose.onNodeWithText("Remove client").performClick()
        confirmRemoval()

        assertEquals(Routes.ROSTER, nav.currentBackStackEntry?.destination?.route)
        jordanGone()
    }
}
