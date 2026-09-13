package com.dugcanlift.coach

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.ui.ClientScreen
import com.dugcanlift.coach.ui.ConnectScreen
import com.dugcanlift.coach.ui.RosterScreen
import java.util.Base64

/** The app's three routes: the roster, one client's detail, and Connect. */
object Routes {
    const val ROSTER = "roster"
    const val CLIENT = "client/{clientId}"
    const val CONNECT = "connect"
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
 */
@Composable
fun CoachNavHost(
    repo: ClientRepository,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    pendingImportFragment: String? = null,
    onImportHandled: () -> Unit = {}
) {
    LaunchedEffect(pendingImportFragment) {
        if (pendingImportFragment != null && navController.currentDestination?.route != Routes.ROSTER) {
            navController.popBackStack(Routes.ROSTER, inclusive = false)
        }
    }
    NavHost(navController = navController, startDestination = Routes.ROSTER, modifier = modifier) {
        composable(Routes.ROSTER) {
            RosterScreen(
                repo = repo,
                onOpen = { clientId -> navController.navigate(Routes.client(clientId)) },
                onImport = { /* import itself is handled inside RosterScreen; this hook is for callers that need to react to a raw import too */ },
                onConnect = { navController.navigate(Routes.CONNECT) },
                pendingImportFragment = pendingImportFragment,
                onImportHandled = onImportHandled
            )
        }
        composable(Routes.CLIENT) { backStackEntry ->
            val clientId = Routes.decodeClientId(backStackEntry.arguments?.getString(Routes.CLIENT_ID_ARG).orEmpty()).orEmpty()
            ClientScreen(clientId = clientId, repo = repo, onBack = { navController.popBackStack() })
        }
        composable(Routes.CONNECT) {
            ConnectScreen(repo = repo, onBack = { navController.popBackStack() })
        }
    }
}
