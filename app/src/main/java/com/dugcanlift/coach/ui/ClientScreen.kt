package com.dugcanlift.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.ClientRepository

/**
 * Placeholder for one client's detail (volume, e1RM, fuel, weekly buckets from [com.dugcanlift.coach.data.Stats]).
 * Task 11 replaces this body; the signature is fixed so [com.dugcanlift.coach.Nav]'s `client/{id}`
 * route doesn't need to change when it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(clientId: String, repo: ClientRepository, onBack: () -> Unit) {
    val client = repo.get(clientId)
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(client?.name ?: clientId) })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Client detail is coming in a later task.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
