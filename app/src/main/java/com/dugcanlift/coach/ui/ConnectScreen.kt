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
 * Placeholder for Connect (share links out, back up, restore -- whatever Task 13's design settles
 * on). The signature is fixed so [com.dugcanlift.coach.Nav]'s `connect` route doesn't need to
 * change when Task 13 fills this in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(repo: ClientRepository, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connect") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Connect is coming in a later task.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
