package com.dugcanlift.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.fragmentFrom

/**
 * The paste-a-link bottom sheet. Accepts either a full share URL or a bare fragment -- the text
 * field takes whatever the client pasted, and Import reduces it with [fragmentFrom] before handing
 * it to [onSubmit], so callers always receive a clean fragment ready for `ShareLinkImporter.import`.
 * Title, field label and button wording match Coach iOS's `PasteLinkView` exactly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteLinkSheet(onSubmit: (String) -> Unit, onDismissRequest: () -> Unit = {}) {
    var text by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Text("Import Log", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Paste the link a client sent you") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSubmit(fragmentFrom(text)) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
