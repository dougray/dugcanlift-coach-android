package com.dugcanlift.coach.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * The editors (recipe, routine) are long forms in a dialog. A tap beside one
 * used to dismiss it and throw the whole form away, so they no longer close on
 * an outside tap, and Back or Cancel with changes asks first.
 */
internal val EditorDialogProperties = DialogProperties(dismissOnClickOutside = false)

@Composable
internal fun DiscardChangesDialog(what: String, onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Discard changes to $what?") },
        text = { Text("What you typed here has not been saved.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep Editing") } }
    )
}
