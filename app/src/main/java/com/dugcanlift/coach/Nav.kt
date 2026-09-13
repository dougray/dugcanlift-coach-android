package com.dugcanlift.coach

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.ui.ClientScreen
import com.dugcanlift.coach.ui.ConnectScreen
import com.dugcanlift.coach.ui.RosterScreen

/** The app's three routes: the roster, one client's detail, and Connect. */
object Routes {
    const val ROSTER = "roster"
    const val CLIENT = "client/{clientId}"
    const val CONNECT = "connect"
    const val CLIENT_ID_ARG = "clientId"

    fun client(clientId: String) = "client/$clientId"
}

@Composable
fun CoachNavHost(
    repo: ClientRepository,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    NavHost(navController = navController, startDestination = Routes.ROSTER, modifier = modifier) {
        composable(Routes.ROSTER) {
            RosterScreen(
                repo = repo,
                onOpen = { clientId -> navController.navigate(Routes.client(clientId)) },
                onImport = { /* import itself is handled inside RosterScreen; this hook is for callers that need to react to a raw import too */ },
                onConnect = { navController.navigate(Routes.CONNECT) }
            )
        }
        composable(Routes.CLIENT) { backStackEntry ->
            val clientId = backStackEntry.arguments?.getString(Routes.CLIENT_ID_ARG).orEmpty()
            ClientScreen(clientId = clientId, repo = repo, onBack = { navController.popBackStack() })
        }
        composable(Routes.CONNECT) {
            ConnectScreen(repo = repo, onBack = { navController.popBackStack() })
        }
    }
}
