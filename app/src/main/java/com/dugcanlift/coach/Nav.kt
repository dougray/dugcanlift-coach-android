package com.dugcanlift.coach

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dugcanlift.coach.ui.adaptive.AdaptiveLayout
import com.dugcanlift.coach.ui.adaptive.CoachNavigationRail
import com.dugcanlift.coach.ui.adaptive.LocalWindowWidth
import com.dugcanlift.coach.ui.adaptive.RosterReconcile
import com.dugcanlift.coach.ui.adaptive.TopLevel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.ui.ClientScreen
import com.dugcanlift.coach.ui.ConnectScreen
import com.dugcanlift.coach.ui.CookScreen
import com.dugcanlift.coach.ui.TrainScreen
import com.dugcanlift.coach.ui.RosterScreen
import java.util.Base64

/** The app's routes: the roster, one client's detail, Cook, and Connect. */
object Routes {
    const val ROSTER = "roster"
    const val CLIENT = "client/{clientId}"
    const val CONNECT = "connect"
    const val COOK = "cook"
    const val TRAIN = "train"
    const val CLIENT_ID_ARG = "clientId"

    /**
     * The client id goes into a route *segment*, and it comes off an untrusted link: SHARE-FORMAT.md
     * puts no charset constraint on `c.i`. Interpolating it raw meant an id containing `%` (or `#`,
     * or `/`) produced a route that did not round-trip back through
     * `backStackEntry.arguments?.getString("clientId")`, so `repo.get` missed and the coach saw
     * "This client's data couldn't be loaded." for a client that was sitting right there, with no
     * way to ever open them.
     *
     * Base64url without padding, rather than percent-encoding: its alphabet is exactly
     * `[A-Za-z0-9_-]`, so there is nothing left for Navigation's own URI decoding to decode, and no
     * double-decoding hazard in either direction. Nothing persists a route, so this changes no
     * stored state.
     */
    fun client(clientId: String) = "client/${encodeClientId(clientId)}"

    /** Cook is the coach's own, not any one client's: the recipe library belongs to them, and
     *  which client a week is for is picked inside the Plan section. So no id in the route. */
    const val cook = COOK

    fun encodeClientId(clientId: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(clientId.toByteArray(Charsets.UTF_8))

    /** The inverse of [encodeClientId]; null for a segment that is not one this app produced. */
    fun decodeClientId(segment: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8) }.getOrNull()
}

/**
 * @param pendingImportFragment a tap-to-import fragment (see [com.dugcanlift.coach.MainActivity])
 *   waiting to be imported by [RosterScreen]. If the coach is on another screen when it arrives,
 *   this pops back to the roster first so the import's snackbar feedback is always visible.
 * @param onImportHandled called once [RosterScreen] has submitted the pending import above.
 *
 * Layout follows [LocalWindowWidth] (see `ui/adaptive/WindowLayout.kt`). Compact is the phone app
 * as it always was: a bottom bar on the roster and Back on every pushed screen. From medium up a
 * [CoachNavigationRail] leads to the four top-level screens, and at expanded the roster shows the
 * selected client beside the list instead of pushing `client/{id}`.
 *
 * [selectedClientId] is "the client currently open", in either form, and survives recreation.
 * [AdaptiveLayout.reconcileRoster] keeps it and the back stack in step when the window crosses
 * the two-pane width -- unfolding a phone showing a client, or narrowing a split screen.
 */
@Composable
fun CoachNavHost(
    repo: ClientRepository,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    pendingImportFragment: String? = null,
    onImportHandled: () -> Unit = {}
) {
    val width = LocalWindowWidth.current
    val useRail = AdaptiveLayout.usesNavigationRail(width)
    val twoPane = AdaptiveLayout.rosterIsTwoPane(width)
    val showBack = AdaptiveLayout.showsBackOnTopLevelScreens(width)

    var selectedClientId by rememberSaveable { mutableStateOf<String?>(null) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    LaunchedEffect(pendingImportFragment) {
        if (pendingImportFragment != null && navController.currentDestination?.route != Routes.ROSTER) {
            navController.popBackStack(Routes.ROSTER, inclusive = false)
        }
    }

    // Saveable, so a recreation that crosses 840 dp still reads as a width change, not a Back.
    var lastTwoPane by rememberSaveable { mutableStateOf(twoPane) }
    LaunchedEffect(twoPane, backStackEntry) {
        val action = AdaptiveLayout.reconcileRoster(
            twoPane = twoPane,
            widthChanged = lastTwoPane != twoPane,
            onClientRoute = route == Routes.CLIENT,
            onRosterRoute = route == Routes.ROSTER,
            selectedClientId = selectedClientId
        )
        lastTwoPane = AdaptiveLayout.rosterTwoPaneToRemember(
            lastTwoPane = lastTwoPane,
            twoPane = twoPane,
            // The first check after a recreation has an entry but no destination route yet.
            backStackRestored = route != null
        )
        when (action) {
            RosterReconcile.SHOW_IN_DETAIL_PANE -> {
                selectedClientId = Routes.decodeClientId(
                    backStackEntry?.arguments?.getString(Routes.CLIENT_ID_ARG).orEmpty()
                )
                navController.popBackStack(Routes.ROSTER, inclusive = false)
            }
            RosterReconcile.OPEN_CLIENT_SCREEN ->
                selectedClientId?.let { navController.navigate(Routes.client(it)) }
            RosterReconcile.CLEAR_SELECTION -> selectedClientId = null
            RosterReconcile.NONE -> Unit
        }
    }

    fun openTopLevel(destination: TopLevel) {
        if (destination == TopLevel.ROSTER) {
            navController.popBackStack(Routes.ROSTER, inclusive = false)
            return
        }
        val target = when (destination) {
            TopLevel.TRAIN -> Routes.TRAIN
            TopLevel.COOK -> Routes.COOK
            else -> Routes.CONNECT
        }
        // Peers, not a stack: Train then Cook then Back lands on the roster, not on Train.
        navController.navigate(target) {
            popUpTo(Routes.ROSTER)
            launchSingleTop = true
        }
    }

    Row(modifier = modifier) {
        if (useRail) {
            CoachNavigationRail(
                current = when (route) {
                    Routes.TRAIN -> TopLevel.TRAIN
                    Routes.COOK -> TopLevel.COOK
                    Routes.CONNECT -> TopLevel.CONNECT
                    else -> TopLevel.ROSTER
                },
                onSelect = ::openTopLevel
            )
        }
        NavHost(
            navController = navController,
            startDestination = Routes.ROSTER,
            // The rail has taken the start inset (a cutout in landscape); the screens beside it
            // must not pad for it a second time.
            modifier = Modifier.weight(1f).fillMaxHeight().then(
                if (useRail) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                else Modifier
            )
        ) {
            composable(Routes.ROSTER) {
                RosterScreen(
                    repo = repo,
                    onOpen = { clientId ->
                        selectedClientId = clientId
                        if (!twoPane) navController.navigate(Routes.client(clientId))
                    },
                    onImport = { /* import itself is handled inside RosterScreen; this hook is for callers that need to react to a raw import too */ },
                    onConnect = { navController.navigate(Routes.CONNECT) },
                    onCook = { navController.navigate(Routes.COOK) },
                    onTrain = { navController.navigate(Routes.TRAIN) },
                    pendingImportFragment = pendingImportFragment,
                    onImportHandled = onImportHandled,
                    showBottomBar = !useRail,
                    twoPane = twoPane,
                    selectedClientId = selectedClientId,
                    detail = { clientId, reloadKey ->
                        ClientScreen(
                            clientId = clientId,
                            repo = repo,
                            onBack = {},
                            onCook = { openTopLevel(TopLevel.COOK) },
                            showBack = false,
                            reloadKey = reloadKey
                        )
                    }
                )
            }
            composable(Routes.CLIENT) { entry ->
                val clientId = Routes.decodeClientId(entry.arguments?.getString(Routes.CLIENT_ID_ARG).orEmpty()).orEmpty()
                ClientScreen(
                    clientId = clientId,
                    repo = repo,
                    onBack = {
                        selectedClientId = null
                        navController.popBackStack()
                    },
                    onCook = { navController.navigate(Routes.COOK) }
                )
            }
            composable(Routes.COOK) {
                CookScreen(repo = repo, onBack = { navController.popBackStack() }, showBack = showBack)
            }
            composable(Routes.TRAIN) {
                TrainScreen(repo = repo, onBack = { navController.popBackStack() }, showBack = showBack)
            }
            composable(Routes.CONNECT) {
                ConnectScreen(repo = repo, onBack = { navController.popBackStack() }, showBack = showBack)
            }
        }
    }
}
