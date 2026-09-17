package com.dugcanlift.coach.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dugcanlift.coach.data.ClientRemoval
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.data.RemovalImpact
import com.dugcanlift.coach.data.RemovalOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Remove <name>?" -- names the client and says what goes before anything does, then removes them
 * through [ClientRemoval]. Shared by the client page's Remove this client and the roster row's
 * long-press menu, so both say and do exactly the same thing.
 *
 * @param onDone called once with the outcome after Remove, whether or not it fully succeeded.
 */
@Composable
fun RemoveClientDialog(
    clientId: String,
    repo: ClientRepository,
    onDismiss: () -> Unit,
    onDone: (RemovalOutcome) -> Unit
) {
    val context = LocalContext.current
    val removal = remember(repo) { ClientRemoval(repo, context.filesDir) }
    val scope = rememberCoroutineScope()
    var impact by remember(clientId) { mutableStateOf<RemovalImpact?>(null) }
    var loaded by remember(clientId) { mutableStateOf(false) }
    var working by remember(clientId) { mutableStateOf(false) }

    LaunchedEffect(clientId) {
        impact = withContext(Dispatchers.IO) { removal.impact(clientId) }
        loaded = true
    }

    // Nothing to show until the counts are in, rather than a dialog whose text changes under a thumb.
    if (!loaded) return
    val current = impact
    if (current == null) {
        // Already gone (removed from another pane, or restored over). Say so rather than offer Remove.
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Client not found") },
            text = { Text("This client is no longer on this device.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Remove ${current.clientName}?") },
        text = { Text(ClientRemoval.confirmationText(current)) },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    working = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { removal.remove(clientId) }
                        onDone(outcome)
                    }
                }
            ) { Text("Remove") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Keep") } }
    )
}
