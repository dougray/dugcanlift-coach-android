package com.dugcanlift.coach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.data.ImportResult
import com.dugcanlift.coach.data.Roster
import com.dugcanlift.coach.data.RosterLoader
import com.dugcanlift.coach.data.RosterRow
import com.dugcanlift.coach.data.ShareLinkImporter
import com.dugcanlift.coach.ui.theme.DclAccent
import com.dugcanlift.coach.ui.theme.DclMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The coach's roster: clients quietest-first, a banner naming anyone silent 7+ days, and the
 * paste-a-link entry point (a bottom sheet, or the empty state's own button when there are no
 * clients yet). All the sorting/labelling/banner logic lives in [Roster] -- this composable only
 * renders [Roster.buildViewState]'s output and reacts to user actions.
 *
 * @param onOpen called with a client's id when its row is tapped (navigates to `client/{id}`).
 * @param onImport called with the raw pasted fragment whenever an import is attempted, in
 *   addition to this screen performing the import itself (it already holds [repo]) -- a hook for
 *   callers that need to react to an import attempt beyond this screen's own snackbar/refresh.
 * @param onConnect called when the bottom bar's Connect action is tapped.
 * @param pendingImportFragment a fragment extracted from a tap-to-import intent (App Links or a
 *   share-sheet target) waiting to be imported -- see [com.dugcanlift.coach.MainActivity]. Handled
 *   exactly once via a [LaunchedEffect] keyed on its value, then [onImportHandled] is called to
 *   clear it so the same fragment is never imported twice (e.g. if this screen recomposes, or the
 *   activity is recreated on rotation and redelivers null here since [MainActivity] only sets a
 *   fresh value from a genuinely new intent).
 * @param onImportHandled called once the pending import above has been submitted, so the caller
 *   can clear it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    repo: ClientRepository,
    onOpen: (String) -> Unit,
    onImport: (String) -> Unit,
    onConnect: () -> Unit = {},
    onCook: () -> Unit = {},
    pendingImportFragment: String? = null,
    onImportHandled: () -> Unit = {}
) {
    var clients by remember { mutableStateOf<List<Client>>(emptyList()) }
    var showPasteSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Serialises this screen's loads of `clients` -- see RosterLoader's doc for why this exists.
    // A cold launch through an App Link or the share sheet starts the initial load below and the
    // pending import's post-import reload at effectively the same time, with no ordering between
    // their two Dispatchers.IO reads; without this, whichever finishes last wins, and a slow
    // initial read can overwrite the just-imported client even though it was issued first.
    val loader = remember { RosterLoader() }

    val viewState = remember(clients) { Roster.buildViewState(clients) }

    // Loads off the main thread; re-fires (fresh disk read) every time this composable is entered
    // fresh -- including on return from ClientScreen, since navigating away disposes this
    // composition and navigating back re-runs it -- so the roster always reflects the latest saves.
    LaunchedEffect(Unit) {
        clients = loader.refresh { withContext(Dispatchers.IO) { repo.all() } }
    }

    fun handleSubmit(fragment: String) {
        onImport(fragment)
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { ShareLinkImporter.import(fragment, repo) }) {
                is ImportResult.Imported -> {
                    clients = loader.refresh { withContext(Dispatchers.IO) { repo.all() } }
                    showPasteSheet = false
                    snackbarHostState.showSnackbar("Imported ${result.daysImported} days for ${result.clientName}")
                }
                ImportResult.UnsupportedVersion ->
                    snackbarHostState.showSnackbar("That link is from a newer LIFT — update Coach.")
                ImportResult.Malformed ->
                    snackbarHostState.showSnackbar(
                        "That doesn't look like a valid LIFT log link. Double-check you copied the whole thing."
                    )
            }
        }
    }

    // Tap-to-import: submit exactly once per non-null value, then hand it back to the caller to
    // clear -- this is what keeps a rotation (which redelivers the same Composable state but not a
    // fresh pendingImportFragment; see MainActivity) from importing the same link twice.
    LaunchedEffect(pendingImportFragment) {
        pendingImportFragment?.let { fragment ->
            handleSubmit(fragment)
            onImportHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Roster") },
                actions = {
                    TextButton(onClick = { showPasteSheet = true }) { Text("Paste a Link") }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        bottomBar = {
            BottomAppBar {
                // Cook sits beside Connect rather than inside a client, matching
                // Coach iOS's tab bar. The recipe library belongs to the coach,
                // not to any one client, so reaching it through a client made the
                // library look like it was theirs -- and made it unreachable at
                // all until the coach had imported someone.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCook) { Text("Cook") }
                    TextButton(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    ) { padding ->
        if (viewState.rows.isEmpty()) {
            RosterEmptyState(
                modifier = Modifier.padding(padding).fillMaxSize(),
                onPasteClick = { showPasteSheet = true }
            )
        } else {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (viewState.silentNames.isNotEmpty()) {
                    SilenceBanner(names = viewState.silentNames)
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(viewState.rows, key = { it.client.id }) { row ->
                        RosterRowItem(row = row, onClick = { onOpen(row.client.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showPasteSheet) {
        PasteLinkSheet(
            onSubmit = ::handleSubmit,
            onDismissRequest = { showPasteSheet = false }
        )
    }
}

@Composable
private fun SilenceBanner(names: List<String>, modifier: Modifier = Modifier) {
    val noun = if (names.size == 1) "client" else "clients"
    Text(
        text = "${names.size} $noun logged nothing in a week: ${names.joinToString(", ")}",
        style = MaterialTheme.typography.bodyMedium,
        color = DclAccent,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun RosterRowItem(row: RosterRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = row.client.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if ((row.daysSinceLastLogged ?: Int.MAX_VALUE) >= Roster.SILENCE_THRESHOLD_DAYS) DclAccent else DclMuted
        )
    }
}

@Composable
private fun RosterEmptyState(onPasteClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No Clients Yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Paste a log link from a client to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = DclMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        // Filled, not text: the browser build renders a primary action as a
        // solid accent pill. On an empty roster this is the only thing to do,
        // and a text button reads as optional. The one in the top bar stays a
        // text button -- a filled pill in a toolbar shouts.
        Button(onClick = onPasteClick) { Text("Paste a Link") }
    }
}
